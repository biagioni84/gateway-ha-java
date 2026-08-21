package uy.plomo.gateway.homeassistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Low-level WebSocket client for Home Assistant's Core API.
 *
 *   ws://<host>:8123/api/websocket  (standalone)
 *   ws://supervisor/core/websocket  (Supervisor-managed addon)
 *
 * Unlike python-matter-server, HA requires an explicit auth handshake before any command
 * is accepted: server sends "auth_required" -> client replies "auth" with the access token
 * -> server replies "auth_ok"/"auth_invalid". Only after "auth_ok" do we subscribe to
 * state_changed events and fetch the initial state dump via get_states.
 *
 * Hook registry: ConcurrentHashMap<id, CompletableFuture<JsonNode>>, keyed by the outgoing
 * message's integer id — mirrors the ZWaveInterface/ZigbeeInterface/MatterInterface pattern.
 */
@Component
@Slf4j
public class HomeAssistantInterface extends TextWebSocketHandler {

    private static final int DEFAULT_TIMEOUT_MS = 10_000;
    private static final int RECONNECT_BASE_MS  =   5_000;
    private static final int RECONNECT_MAX_MS   = 300_000; // 5 min cap

    /** Current backoff delay; doubles on each failed attempt, resets on success. */
    private volatile int reconnectDelayMs = RECONNECT_BASE_MS;
    /** Guards against concurrent connect() calls from backoff + watchdog. */
    private final AtomicBoolean connecting = new AtomicBoolean(false);

    private final ObjectMapper objectMapper;
    private final HomeAssistantConnectionConfig connectionConfig;

    private volatile WebSocketSession session;
    private volatile boolean running;
    private volatile boolean authenticated;

    // message id -> pending CompletableFuture
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    // entity_id -> live state (populated on get_states + state_changed events)
    private final ConcurrentHashMap<String, HAState> states = new ConcurrentHashMap<>();

    private final AtomicLong messageIdCounter = new AtomicLong(1);

    // Injected post-construction to avoid circular dep (mirrors MatterInterface pattern)
    private HomeAssistantReportHandler reportHandler;

    @Autowired
    @Qualifier("gatewayExecutor")
    private Executor eventExecutor;

    public HomeAssistantInterface(ObjectMapper objectMapper, HomeAssistantConnectionConfig connectionConfig) {
        this.objectMapper = objectMapper;
        this.connectionConfig = connectionConfig;
    }

    public void setReportHandler(HomeAssistantReportHandler h) {
        this.reportHandler = h;
        // If get_states already completed before the handler was wired in, notify now
        if (!states.isEmpty()) {
            h.onInitialStates(states.values());
        }
    }

    public boolean isEnabled() { return connectionConfig.isEnabled(); }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @PostConstruct
    public void start() {
        if (!connectionConfig.isEnabled()) {
            log.info("Home Assistant disabled (homeassistant.enabled=false)");
            return;
        }
        running = true;
        connect();
        // Safety-net watchdog: catches edge cases where afterConnectionClosed doesn't fire.
        Scheduler.INSTANCE.scheduleWithFixedDelay(
                this::watchdog, 300_000L, 300_000L, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void stop() {
        running = false;
        closeSession();
        log.info("Home Assistant client stopped");
    }

    private void connect() {
        if (!connecting.compareAndSet(false, true)) {
            log.debug("Home Assistant: connect already in progress — skipping");
            return;
        }
        try {
            // HA's get_states dump can be as large as python-matter-server's start_listening
            // dump for installs with hundreds of entities — bump the default 8 KB WS buffer.
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            container.setDefaultMaxTextMessageBufferSize(10 * 1024 * 1024);
            StandardWebSocketClient client = new StandardWebSocketClient(container);
            client.execute(this, connectionConfig.getWsUrl()).get(5, TimeUnit.SECONDS);
            // Success: afterConnectionEstablished will reset the delay and clear `connecting`
        } catch (Exception e) {
            connecting.set(false);
            log.warn("Home Assistant: connect to {} failed — {}", connectionConfig.getWsUrl(), e.getMessage());
            scheduleReconnect();
        }
    }

    /** Exponential backoff: 5 s -> 10 s -> 20 s -> ... -> 300 s, +-20% jitter. */
    private void scheduleReconnect() {
        if (!running) return;
        int delay = reconnectDelayMs;
        reconnectDelayMs = (int) Math.min((long) reconnectDelayMs * 2, RECONNECT_MAX_MS);
        int jitter = (int) (delay * 0.20 * (ThreadLocalRandom.current().nextDouble() * 2 - 1));
        int effective = Math.max(1_000, delay + jitter);
        log.info("Home Assistant: reconnecting in {}ms (backoff={}ms)", effective, delay);
        Scheduler.INSTANCE.schedule(
                () -> eventExecutor.execute(this::connect), effective, TimeUnit.MILLISECONDS);
    }

    /** Safety-net: fires every 5 min to catch edge cases where afterConnectionClosed didn't fire. */
    private void watchdog() {
        if (!running) return;
        if (session == null || !session.isOpen()) {
            log.warn("Home Assistant: watchdog detected dead session");
            scheduleReconnect();
        }
    }

    private void closeSession() {
        WebSocketSession s = session;
        if (s != null && s.isOpen()) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    // ── WebSocketHandler callbacks ────────────────────────────────────────────

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        this.session = session;
        authenticated = false;
        reconnectDelayMs = RECONNECT_BASE_MS; // reset backoff on successful connect
        connecting.set(false);
        log.info("Home Assistant: connected, awaiting auth_required");
        // Do NOT send anything yet — HA drives the handshake by sending auth_required first.
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.warn("Home Assistant: connection closed — {}", status);
        this.session = null;
        authenticated = false;
        connecting.set(false);
        pending.forEach((id, f) ->
                f.completeExceptionally(new IOException("Home Assistant WS disconnected")));
        pending.clear();
        scheduleReconnect();
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        log.debug("HA<< {}", payload);
        try {
            HomeAssistantMessage msg = objectMapper.readValue(payload, HomeAssistantMessage.class);
            if (msg.isAuthRequired()) {
                sendAuth();
            } else if (msg.isAuthOk()) {
                onAuthenticated();
            } else if (msg.isAuthInvalid()) {
                log.error("Home Assistant: authentication failed — {}", msg.getMessage());
            } else if (msg.isResult()) {
                handleResult(msg);
            } else if (msg.isEvent()) {
                handleEventMessage(msg);
            } else {
                log.debug("Home Assistant: unhandled message type '{}'", msg.getType());
            }
        } catch (Exception e) {
            log.error("Home Assistant: error parsing: {}", payload, e);
        }
    }

    // ── Auth handshake ────────────────────────────────────────────────────────

    private void sendAuth() {
        WebSocketSession s = session;
        if (s == null || !s.isOpen()) return;
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("type", "auth");
            node.put("access_token", connectionConfig.getToken());
            String json = objectMapper.writeValueAsString(node);
            synchronized (s) {
                s.sendMessage(new TextMessage(json));
            }
        } catch (Exception e) {
            log.error("Home Assistant: failed to send auth frame", e);
        }
    }

    private void onAuthenticated() {
        authenticated = true;
        log.info("Home Assistant: authenticated");
        // subscribe first, then dump current state — avoids missing changes in between.
        doSend(messageIdCounter.getAndIncrement(), "subscribe_events",
                Map.of("event_type", "state_changed"));
        sendCommandWait("get_states", null)
                .thenAccept(this::populateStateCache)
                .exceptionally(ex -> {
                    log.warn("Home Assistant: get_states failed — {}", ex.getMessage());
                    return null;
                });
    }

    // ── Response / event handling ─────────────────────────────────────────────

    private void handleResult(HomeAssistantMessage msg) {
        Long id = msg.getId();
        if (id == null) return;
        CompletableFuture<JsonNode> future = pending.remove(id);
        if (future == null) return; // e.g. the subscribe_events ack — nothing waits on it

        if (Boolean.TRUE.equals(msg.getSuccess())) {
            future.complete(msg.getResult());
        } else {
            future.completeExceptionally(HomeAssistantException.from(msg.getError()));
        }
    }

    private void handleEventMessage(HomeAssistantMessage msg) {
        JsonNode event = msg.getEvent();
        if (event == null) return;
        String eventType = event.path("event_type").asText(null);
        JsonNode data = event.path("data");

        if ("state_changed".equals(eventType)) {
            String entityId = data.path("entity_id").asText(null);
            JsonNode newState = data.path("new_state");
            if (entityId != null && newState != null && !newState.isMissingNode() && !newState.isNull()) {
                states.put(entityId, toHAState(entityId, newState));
            } else if (entityId != null) {
                states.remove(entityId);
            }
        }

        if (reportHandler != null) {
            eventExecutor.execute(() -> reportHandler.handleEvent(eventType, data));
        }
    }

    private void populateStateCache(JsonNode statesArray) {
        if (statesArray == null || !statesArray.isArray()) return;
        states.clear();
        statesArray.forEach(s -> {
            String entityId = s.path("entity_id").asText(null);
            if (entityId != null) states.put(entityId, toHAState(entityId, s));
        });
        log.info("Home Assistant: state cache populated — {} entities", states.size());
        if (reportHandler != null) {
            reportHandler.onInitialStates(states.values());
        }
    }

    private static HAState toHAState(String entityId, JsonNode s) {
        return new HAState(
                entityId,
                s.path("state").asText(null),
                s.path("attributes"),
                s.path("last_changed").asText(null),
                s.path("last_updated").asText(null));
    }

    // ── Command sending ───────────────────────────────────────────────────────

    public CompletableFuture<JsonNode> sendCommandWait(String type, Map<String, Object> fields) {
        return sendCommandWait(type, fields, DEFAULT_TIMEOUT_MS);
    }

    public CompletableFuture<JsonNode> sendCommandWait(String type, Map<String, Object> fields, int timeoutMs) {
        long id = messageIdCounter.getAndIncrement();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);

        doSend(id, type, fields);

        Scheduler.INSTANCE.schedule(() -> {
            if (future.completeExceptionally(
                    new TimeoutException("Home Assistant '" + type + "' timed out after " + timeoutMs + "ms"))) {
                pending.remove(id);
                log.warn("Home Assistant: command '{}' timed out ({}ms)", type, timeoutMs);
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);

        return future.whenComplete((r, ex) -> pending.remove(id));
    }

    /**
     * Calls a Home Assistant service, e.g. domain="light", service="turn_on".
     * entityId may be null for services that don't target a specific entity.
     */
    public CompletableFuture<JsonNode> callService(
            String domain, String service, String entityId, Map<String, Object> serviceData) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("domain", domain);
        fields.put("service", service);
        if (entityId != null) fields.put("target", Map.of("entity_id", entityId));
        if (serviceData != null && !serviceData.isEmpty()) fields.put("service_data", serviceData);
        return sendCommandWait("call_service", fields);
    }

    private void doSend(long id, String type, Map<String, Object> fields) {
        WebSocketSession s = session;
        if (s == null || !s.isOpen()) {
            log.warn("Home Assistant: not connected — dropping: {}", type);
            CompletableFuture<JsonNode> f = pending.remove(id);
            if (f != null) f.completeExceptionally(new IOException("Home Assistant WS not connected"));
            return;
        }
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("id", id);
            node.put("type", type);
            if (fields != null) {
                fields.forEach((k, v) -> node.set(k, objectMapper.valueToTree(v)));
            }
            String json = objectMapper.writeValueAsString(node);
            log.debug("HA>> {}", json);
            // WebSocketSession.sendMessage is not thread-safe for concurrent writes
            synchronized (s) {
                s.sendMessage(new TextMessage(json));
            }
        } catch (Exception e) {
            log.error("Home Assistant: send error for '{}'", type, e);
            CompletableFuture<JsonNode> f = pending.remove(id);
            if (f != null) f.completeExceptionally(e);
        }
    }

    // ── State cache access ────────────────────────────────────────────────────

    public Map<String, HAState> getStatesCache()   { return states; }
    public HAState              getState(String entityId) { return states.get(entityId); }
    public boolean               isAuthenticated()  { return authenticated; }

    // ── Scheduler ─────────────────────────────────────────────────────────────

    private static class Scheduler {
        static final ScheduledExecutorService INSTANCE =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "homeassistant-scheduler");
                    t.setDaemon(true);
                    return t;
                });
    }
}

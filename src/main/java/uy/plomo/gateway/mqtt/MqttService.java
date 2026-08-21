package uy.plomo.gateway.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.crt.CRT;
import software.amazon.awssdk.crt.mqtt5.*;
import software.amazon.awssdk.crt.mqtt5.packets.*;
import software.amazon.awssdk.iot.AwsIotMqtt5ClientBuilder;
import uy.plomo.gateway.config.AppConfig;
import uy.plomo.gateway.platform.PlatformService;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AWS IoT MQTT5 client — gateway role.
 *
 * Unlike cloud-side (WebSocket + SigV4), the gateway authenticates with mTLS
 * using the certificate provisioned via AWS IoT Fleet Provisioning.
 *
 * Topics:
 *   Subscribe: iot/v1/{name}/request/#
 *   Subscribe: $aws/things/{name}/tunnels/notify
 *   Publish:   iot/v1/{name}/response/{requestId}
 *   Publish:   iot/v1/{name}/event/{timestamp}
 *
 * Incoming message format:
 *   { "path": "GET:/summary", "command": "{...json body...}" }
 */
@Service
@Slf4j
public class MqttService {

    private static final int CONNECT_TIMEOUT_SECONDS = 30;
    private static final long RECONNECT_MIN_MS =   1_000;
    private static final long RECONNECT_MAX_MS = 300_000; // 5 min — limits hammering during mass outages

    @Value("${aws.iot.endpoint}")
    private String endpoint;

    private final AppConfig              appConfig;
    private final AsyncCommandDispatcher asyncDispatcher;
    private final PlatformService        platformService;
    private final ObjectMapper           objectMapper;

    private Mqtt5Client client;
    private String gatewayName;

    private final AtomicBoolean everConnected = new AtomicBoolean(false);
    private final AtomicBoolean connected     = new AtomicBoolean(false);

    private static final Pattern REQUEST_PATTERN =
            Pattern.compile("iot/v1/(.+)/request/(.+)");

    public MqttService(AppConfig appConfig,
                       @Lazy AsyncCommandDispatcher asyncDispatcher,
                       PlatformService platformService, ObjectMapper objectMapper) {
        this.appConfig       = appConfig;
        this.asyncDispatcher = asyncDispatcher;
        this.platformService = platformService;
        this.objectMapper    = objectMapper;
    }

    @PostConstruct
    public void init() {
        if ("disabled".equalsIgnoreCase(endpoint)) {
            log.info("MQTT disabled (test profile)");
            return;
        }
        if (!appConfig.getCreds().isComplete()) {
            log.warn("Gateway is not provisioned — MQTT client will not start. " +
                     "Run the provisioning flow to generate credentials.");
            return;
        }
        gatewayName = appConfig.getCreds().getName();
        startClient();
    }

    // ── Client lifecycle ──────────────────────────────────────────────────────

    private void startClient() {
        log.info("Initializing MQTT5 client (mTLS) for gateway '{}', endpoint: {}", gatewayName, endpoint);

        CountDownLatch initialConnect = new CountDownLatch(1);

        Mqtt5ClientOptions.LifecycleEvents lifecycle = new Mqtt5ClientOptions.LifecycleEvents() {
            @Override
            public void onAttemptingConnect(Mqtt5Client c, OnAttemptingConnectReturn r) {
                log.info("MQTT connecting to '{}' as '{}'", endpoint, gatewayName);
            }

            @Override
            public void onConnectionSuccess(Mqtt5Client c, OnConnectionSuccessReturn r) {
                log.info("MQTT connected: {}", r.getConnAckPacket().getReasonCode());
                connected.set(true);
                if (everConnected.compareAndSet(false, true)) {
                    initialConnect.countDown();
                } else {
                    log.info("MQTT reconnected — resubscribing");
                    resubscribe();
                }
            }

            @Override
            public void onConnectionFailure(Mqtt5Client c, OnConnectionFailureReturn r) {
                log.error("MQTT connection failed: {} — {}",
                        CRT.awsErrorName(r.getErrorCode()),
                        CRT.awsErrorString(r.getErrorCode()));
            }

            @Override
            public void onDisconnection(Mqtt5Client c, OnDisconnectionReturn r) {
                connected.set(false);
                DisconnectPacket dp = r.getDisconnectPacket();
                if (dp != null) {
                    log.warn("MQTT disconnected: {} — {}", dp.getReasonCode(), dp.getReasonString());
                } else {
                    log.warn("MQTT disconnected (no packet)");
                }
            }

            @Override
            public void onStopped(Mqtt5Client c, OnStoppedReturn r) {
                log.info("MQTT client stopped");
            }
        };

        Mqtt5ClientOptions.PublishEvents publishEvents = (mqttClient, publishReturn) -> {
            PublishPacket pkt = publishReturn.getPublishPacket();
            String topic   = pkt.getTopic();
            String payload = pkt.getPayload() == null ? ""
                    : new String(pkt.getPayload(), StandardCharsets.UTF_8);

            log.debug("MQTT message on '{}': {}", topic, payload);
            handleIncoming(topic, payload);
        };

        AwsIotMqtt5ClientBuilder builder = AwsIotMqtt5ClientBuilder
                .newDirectMqttBuilderWithMtlsFromMemory(
                        endpoint,
                        appConfig.getCreds().getCertPem(),
                        appConfig.getCreds().getPrivateKey());

        builder.withMinReconnectDelayMs(RECONNECT_MIN_MS);
        builder.withMaxReconnectDelayMs(RECONNECT_MAX_MS);
        builder.withLifeCycleEvents(lifecycle);
        builder.withPublishEvents(publishEvents);
        builder.withClientId(gatewayName);

        client = builder.build();
        builder.close();
        client.start();

        try {
            if (!initialConnect.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.error("MQTT connection timeout after {}s — gateway will run without cloud connectivity",
                        CONNECT_TIMEOUT_SECONDS);
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while waiting for MQTT connection");
            return;
        }

        subscribe("iot/v1/" + gatewayName + "/request/#");
        subscribe("$aws/things/" + gatewayName + "/tunnels/notify");
    }

    private void resubscribe() {
        try {
            subscribe("iot/v1/" + gatewayName + "/request/#");
            subscribe("$aws/things/" + gatewayName + "/tunnels/notify");
        } catch (Exception e) {
            log.error("Failed to resubscribe after reconnection", e);
        }
    }

    private void subscribe(String topic) {
        log.info("Subscribing to: {}", topic);
        SubscribePacket pkt = SubscribePacket.of(topic, QOS.AT_MOST_ONCE);
        try {
            SubAckPacket ack = client.subscribe(pkt).get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("Subscribed to '{}', ack: {}", topic, ack.getReasonCodes());
        } catch (Exception e) {
            log.error("Failed to subscribe to '{}'", topic, e);
        }
    }

    // ── Incoming message handling ─────────────────────────────────────────────

    private void handleIncoming(String topic, String payload) {
        String tunnelTopic = "$aws/things/" + gatewayName + "/tunnels/notify";

        if (tunnelTopic.equals(topic)) {
            handleTunnelNotification(payload);
            return;
        }

        Matcher m = REQUEST_PATTERN.matcher(topic);
        if (!m.matches()) {
            log.debug("Unhandled topic: {}", topic);
            return;
        }

        String requestId = m.group(2);

        try {
            com.fasterxml.jackson.databind.JsonNode msg = objectMapper.readTree(payload);
            String pathFull = msg.path("path").asText("");
            String body     = msg.has("command") ? msg.get("command").toString() : "{}";

            // pathFull format: "GET:/summary" or "POST:/include"
            int colon = pathFull.indexOf(':');
            if (colon < 0) {
                log.warn("Invalid path format in MQTT message: '{}' (requestId={})", pathFull, requestId);
                publishResponse(requestId, "{\"error\":\"invalid path format\"}");
                return;
            }

            String method = pathFull.substring(0, colon).toUpperCase();
            String path   = pathFull.substring(colon + 1);

            asyncDispatcher.dispatch(requestId, method, path, body);

        } catch (Exception e) {
            log.error("Error handling MQTT request {}", requestId, e);
            publishResponse(requestId, "{\"error\":\"internal error\"}");
        }
    }

    /**
     * Handles $aws/things/{name}/tunnels/notify.
     *
     * AWS sends this when a Secure Tunnel is opened from the cloud side.
     * Payload (example):
     * {
     *   "clientAccessToken": "...",
     *   "clientMode": "destination",
     *   "region": "us-east-1",
     *   "services": ["SSH"]
     * }
     *
     * The gateway starts `localproxy` in destination mode, which listens on a
     * local port and tunnels traffic to the requested service (typically SSH:22).
     */
    private void handleTunnelNotification(String payload) {
        log.info("AWS Secure Tunnel notification received");
        try {
            com.fasterxml.jackson.databind.JsonNode msg = objectMapper.readTree(payload);

            String clientMode = msg.path("clientMode").asText("");
            if (!"destination".equalsIgnoreCase(clientMode)) {
                log.warn("Unexpected tunnel clientMode '{}' — expected 'destination'", clientMode);
                return;
            }

            String clientAccessToken = msg.path("clientAccessToken").asText("");
            String region            = msg.path("region").asText("us-east-1");

            if (clientAccessToken.isBlank()) {
                log.error("Tunnel notification missing clientAccessToken");
                return;
            }

            // Determine the target local port from the requested service.
            // Default to SSH (22) if the services list is absent or unrecognised.
            int localPort = 22;
            if (msg.has("services")) {
                for (com.fasterxml.jackson.databind.JsonNode svc : msg.get("services")) {
                    if ("SSH".equalsIgnoreCase(svc.asText())) { localPort = 22; break; }
                }
            }

            platformService.startSecureTunnel(region, clientAccessToken, localPort);

        } catch (Exception e) {
            log.error("Failed to handle tunnel notification", e);
        }
    }

    // ── Publish helpers ───────────────────────────────────────────────────────

    void publishResponse(String requestId, String responseJson) {
        String topic = "iot/v1/" + gatewayName + "/response/" + requestId;
        publish(topic, responseJson);
    }

    /**
     * Publish an unsolicited event (device report).
     * Called by report handlers when a device sends data.
     *
     * @param eventPayload serializable object; will be JSON-encoded
     */
    public void publishEvent(Object eventPayload) {
        if (!isConnected()) {
            log.warn("MQTT not connected — dropping event: {}", eventPayload);
            return;
        }
        String topic = "iot/v1/" + gatewayName + "/event/" + Instant.now().toEpochMilli();
        try {
            String json = eventPayload instanceof String s ? s
                    : objectMapper.writeValueAsString(eventPayload);
            publish(topic, json);
        } catch (Exception e) {
            log.error("Failed to serialize event payload", e);
        }
    }

    /**
     * Publish a JSON event directly.
     */
    public void publishEvent(String jsonPayload) {
        if (!isConnected()) {
            log.warn("MQTT not connected — dropping event");
            return;
        }
        String topic = "iot/v1/" + gatewayName + "/event/" + Instant.now().toEpochMilli();
        publish(topic, jsonPayload);
    }

    /**
     * Publish a telemetry batch — all events accumulated since the last flush.
     * Called by TelemetryBuffer on its scheduled interval.
     *
     * Topic: iot/v1/{name}/telemetry/{timestamp}
     * Payload: { "events": [ {...}, ... ] }
     */
    public void publishTelemetry(java.util.List<java.util.Map<String, Object>> events) {
        if (!isConnected()) {
            log.warn("MQTT not connected — dropping telemetry batch ({} events)", events.size());
            return;
        }
        String topic = "iot/v1/" + gatewayName + "/telemetry/" + Instant.now().toEpochMilli();
        try {
            String json = objectMapper.writeValueAsString(java.util.Map.of("events", events));
            publish(topic, json);
        } catch (Exception e) {
            log.error("Failed to serialize telemetry batch", e);
        }
    }

    private void publish(String topic, String payload) {
        if (client == null) return;
        log.debug("Publishing to '{}': {}", topic, payload);
        PublishPacket pkt = PublishPacket.of(
                topic,
                QOS.AT_MOST_ONCE,
                payload.getBytes(StandardCharsets.UTF_8));
        client.publish(pkt)
              .thenRun(() -> log.debug("Published to '{}'", topic))
              .exceptionally(e -> {
                  log.error("Failed to publish to '{}'", topic, e);
                  return null;
              });
    }

    public boolean isConnected() {
        return connected.get();
    }
}

package uy.plomo.gateway.audio;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/**
 * Cliente MQTT local — recibe eventos del inference service Python vía Mosquitto.
 *
 * Topics suscritos:
 *   plomo/{location}/+/events      — eventos de audio detectados por cámara
 *   plomo/{location}/system/status — respuestas a comandos enviados por Java
 *
 * Topics publicados (Clase 13):
 *   plomo/{location}/system/command        — comandos globales a Python
 *   plomo/{location}/{camera_id}/command   — comandos por cámara
 *
 * Contrato completo: integracion.md
 * Habilitado por: audio.mqtt.enabled=true en application.properties
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "audio.mqtt.enabled", havingValue = "true", matchIfMissing = false)
public class AudioMqttClient implements MqttCallbackExtended {

    @Value("${audio.mqtt.broker:localhost}")
    private String broker;

    @Value("${audio.mqtt.port:1883}")
    private int port;

    @Value("${audio.mqtt.location:plomo}")
    private String location;

    private final ObjectMapper        objectMapper;
    private final AudioEventService   eventService;
    private final AudioCommandService commandService;
    private final Executor            executor;

    private MqttClient client;

    public AudioMqttClient(ObjectMapper objectMapper,
                           AudioEventService eventService,
                           AudioCommandService commandService,
                           @Qualifier("gatewayExecutor") Executor executor) {
        this.objectMapper   = objectMapper;
        this.eventService   = eventService;
        this.commandService = commandService;
        this.executor       = executor;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @PostConstruct
    public void start() {
        String brokerUrl = "tcp://" + broker + ":" + port;
        try {
            client = new MqttClient(brokerUrl, MqttClient.generateClientId(),
                    new MemoryPersistence());
            client.setCallback(this);

            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setAutomaticReconnect(true);
            opts.setMaxReconnectDelay(60_000);  // backoff hasta 60s
            opts.setConnectionTimeout(10);
            opts.setKeepAliveInterval(30);
            opts.setCleanSession(true);

            client.connect(opts);
            log.info("Audio MQTT: connected to {}", brokerUrl);
            subscribe();
        } catch (MqttException e) {
            log.warn("Audio MQTT: initial connection to {} failed — will retry: {}",
                    brokerUrl, e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        try {
            if (client != null && client.isConnected()) client.disconnect();
            log.info("Audio MQTT: disconnected");
        } catch (MqttException ignored) {}
    }

    // ── Subscriptions ─────────────────────────────────────────────────────────

    private void subscribe() throws MqttException {
        String eventsTopic = "plomo/" + location + "/+/events";
        String statusTopic = "plomo/" + location + "/system/status";
        client.subscribe(new String[]{ eventsTopic, statusTopic }, new int[]{ 1, 1 });
        log.info("Audio MQTT: subscribed to [{}] and [{}]", eventsTopic, statusTopic);
    }

    // ── MqttCallbackExtended ──────────────────────────────────────────────────

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        if (reconnect) {
            log.info("Audio MQTT: reconnected to {}", serverURI);
            try {
                subscribe();
            } catch (MqttException e) {
                log.error("Audio MQTT: resubscribe failed after reconnect", e);
            }
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("Audio MQTT: connection lost — {} (auto-reconnect active)", cause.getMessage());
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        // Nunca bloquear el thread de Paho — delegar al executor del gateway
        String payload = new String(message.getPayload());
        executor.execute(() -> route(topic, payload));
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {}

    // ── Message routing ───────────────────────────────────────────────────────

    private void route(String topic, String payload) {
        log.debug("Audio MQTT << [{}]: {}", topic, payload);
        try {
            if (topic.endsWith("/events")) {
                AudioEvent event = objectMapper.readValue(payload, AudioEvent.class);
                eventService.handle(event);
            } else if (topic.endsWith("/status")) {
                commandService.handleResponse(payload);
            } else {
                log.debug("Audio MQTT: unhandled topic {}", topic);
            }
        } catch (Exception e) {
            log.error("Audio MQTT: error processing message on topic {}: {}", topic, e.getMessage());
        }
    }

    // ── Command publishing (usado en Clase 13) ────────────────────────────────

    /**
     * Publica un comando al inference service Python.
     *
     * @param subTopic  parte del topic después de "plomo/{location}/" —
     *                  e.g. "system/command" o "cam_01/command"
     * @param payload   JSON del comando
     */
    public void publish(String subTopic, String payload) {
        if (client == null || !client.isConnected()) {
            log.warn("Audio MQTT: not connected — dropping command to {}", subTopic);
            return;
        }
        String fullTopic = "plomo/" + location + "/" + subTopic;
        try {
            client.publish(fullTopic, payload.getBytes(), 1, false);
            log.debug("Audio MQTT >> [{}]: {}", fullTopic, payload);
        } catch (MqttException e) {
            log.error("Audio MQTT: publish to {} failed: {}", fullTopic, e.getMessage());
        }
    }
}

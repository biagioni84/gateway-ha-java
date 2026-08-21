package uy.plomo.gateway.audio;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Envía comandos al inference service Python y espera respuesta.
 *
 * Patrón: command/response sobre MQTT (coherente con MqttDispatcher cloud).
 *   Java publica en: plomo/{location}/system/command
 *   Python responde en: plomo/{location}/system/status
 *
 * La correlación de commandos usa el campo "action" de la respuesta como clave.
 * La correlación de sesiones learn usa learn_id UUID opaco al cloud.
 *
 * Ciclo de dependencia:
 *   AudioCommandService → @Lazy AudioMqttClient → AudioCommandService
 *   Resuelto con @Lazy — Spring inyecta un proxy que se resuelve en runtime.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "audio.mqtt.enabled", havingValue = "true")
public class AudioCommandService {

    private static final long LEARN_SESSION_TTL_MS = 10 * 60 * 1_000L; // 10 min

    private final AudioMqttClient mqttClient;
    private final ObjectMapper    objectMapper;

    /** Futures pendientes: responseAction → future. */
    private final ConcurrentHashMap<String, CompletableFuture<AudioResponse>> pending =
            new ConcurrentHashMap<>();

    /**
     * Sesiones de learn activas: learn_id → LearnSession.
     * Permite al cloud correlacionar learn_ready con el learn_apply posterior.
     */
    private final ConcurrentHashMap<String, LearnSession> pendingLearns =
            new ConcurrentHashMap<>();

    public AudioCommandService(@Lazy AudioMqttClient mqttClient, ObjectMapper objectMapper) {
        this.mqttClient   = mqttClient;
        this.objectMapper = objectMapper;
    }

    // ── API pública ───────────────────────────────────────────────────────────

    public AudioResponse sendStatus() {
        return send(AudioCommand.of("status"), "status", 5);
    }

    public AudioResponse sendReloadConfig() {
        return send(AudioCommand.of("reload_config"), "reload_config", 5);
    }

    /**
     * Inicia grabación en vivo para aprender un nuevo evento.
     * Genera un learn_id UUID que el cloud debe incluir en el learn_apply posterior.
     * Timeout = duration + 15s para absorber latencia de grabación.
     */
    public AudioResponse sendLearn(String cameraId, String eventName, int durationSeconds) {
        String learnId = UUID.randomUUID().toString();
        pendingLearns.put(learnId, new LearnSession(eventName, System.currentTimeMillis()));

        AudioCommand cmd = AudioCommand.builder()
                .action("learn")
                .params(Map.of(
                        "camera_id",  cameraId,
                        "event_name", eventName,
                        "duration",   durationSeconds))
                .build();

        AudioResponse response = send(cmd, "learn_ready", durationSeconds + 15);

        if (response.isOk()) {
            // Inyectar learn_id en la respuesta — el cloud lo usa para el learn_apply
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("learn_id", learnId);
            if (response.getData() != null) data.putAll(response.getData());
            return AudioResponse.builder()
                    .action(response.getAction())
                    .status(response.getStatus())
                    .data(data)
                    .rawPayload(response.getRawPayload())
                    .build();
        }

        // Si falló, limpiar la sesión — no va a llegar un learn_apply
        pendingLearns.remove(learnId);
        return response;
    }

    /** Procesa un clip WAV existente — alternativa a grabación en vivo. */
    public AudioResponse sendLearnFromClip(String clipPath, String eventName) {
        AudioCommand cmd = AudioCommand.builder()
                .action("learn_from_clip")
                .params(Map.of("clip_path", clipPath, "event_name", eventName))
                .build();
        return send(cmd, "learn_ready", 60);
    }

    /**
     * Aplica un entry events.yaml generado por el cloud.
     *
     * @param learnId   UUID retornado en learn_ready — identifica la sesión de aprendizaje.
     *                  Si es null se acepta igual (compatibilidad con calls directas sin learn previo).
     * @param eventName nombre del evento a registrar en events.yaml
     * @param entry     definición del evento generada por el LLM cloud
     */
    public AudioResponse sendLearnApply(String learnId, String eventName, Map<String, Object> entry) {
        if (learnId != null) {
            LearnSession session = pendingLearns.get(learnId);
            if (session == null) {
                log.warn("Audio: unknown learn_id {} — expired or invalid", learnId);
                return AudioResponse.error("learn_apply", "unknown learn_id: " + learnId);
            }
            if (System.currentTimeMillis() - session.startedAt() > LEARN_SESSION_TTL_MS) {
                pendingLearns.remove(learnId);
                log.warn("Audio: learn_id {} expired (TTL 10min)", learnId);
                return AudioResponse.error("learn_apply", "learn_id expired: " + learnId);
            }
        }

        AudioCommand cmd = AudioCommand.builder()
                .action("learn_apply")
                .params(Map.of("event_name", eventName, "entry", entry))
                .build();

        AudioResponse response = send(cmd, "learn_apply", 5);

        // Limpiar sesión independientemente del resultado
        if (learnId != null) pendingLearns.remove(learnId);

        // Incluir learn_id en la confirmación al cloud
        if (learnId != null && response.isOk()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("learn_id", learnId);
            if (response.getData() != null) data.putAll(response.getData());
            return AudioResponse.builder()
                    .action(response.getAction())
                    .status(response.getStatus())
                    .data(data)
                    .rawPayload(response.getRawPayload())
                    .build();
        }

        return response;
    }

    // ── Receive ───────────────────────────────────────────────────────────────

    /**
     * Llamado por AudioMqttClient al recibir un mensaje en el topic /status.
     * Parsea la respuesta y completa el future pendiente por action.
     */
    public void handleResponse(String payload) {
        AudioResponse response;
        try {
            response = AudioResponse.parse(payload, objectMapper);
        } catch (Exception e) {
            log.error("Audio: error parsing command response: {}", e.getMessage());
            return;
        }

        log.debug("Audio command response: action={} status={}", response.getAction(), response.getStatus());

        CompletableFuture<AudioResponse> future = pending.remove(response.getAction());
        if (future != null) {
            future.complete(response);
        } else {
            log.debug("Audio: response with no pending command: action={}", response.getAction());
        }
    }

    // ── Core send ─────────────────────────────────────────────────────────────

    /**
     * Publica el comando y bloquea hasta recibir la respuesta o que expire el timeout.
     *
     * @param cmd            comando a enviar
     * @param responseAction action esperada en la respuesta (puede diferir: "learn" → "learn_ready")
     * @param timeoutSeconds tiempo máximo de espera
     */
    public AudioResponse send(AudioCommand cmd, String responseAction, int timeoutSeconds) {
        CompletableFuture<AudioResponse> future = new CompletableFuture<>();
        pending.put(responseAction, future);

        try {
            String json = cmd.toJson(objectMapper);
            mqttClient.publish("system/command", json);
            log.debug("Audio command >> action={} (expecting response: {})", cmd.getAction(), responseAction);

            return future.get(timeoutSeconds, TimeUnit.SECONDS);

        } catch (TimeoutException e) {
            log.warn("Audio command timeout: action={} after {}s", cmd.getAction(), timeoutSeconds);
            return AudioResponse.error(cmd.getAction(), "timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AudioResponse.error(cmd.getAction(), "interrupted");
        } catch (Exception e) {
            log.error("Audio command error: action={}: {}", cmd.getAction(), e.getMessage());
            return AudioResponse.error(cmd.getAction(), e.getMessage());
        } finally {
            pending.remove(responseAction);
        }
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    private record LearnSession(String eventName, long startedAt) {}
}

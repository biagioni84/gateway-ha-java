package uy.plomo.gateway.audio;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
 * Respuesta del inference service Python a un comando Java.
 *
 * Coherente con el envelope de respuesta cloud:
 *   iot/v1/{name}/response/{requestId} → {"status":"ok","data":{...}}
 *
 * Python no siempre incluye "status" en la respuesta — si está ausente se infiere "ok"
 * (solo las respuestas de error o acciones fallidas lo incluyen explícitamente).
 *
 * rawPayload se conserva para logging y forward sin re-serializar.
 */
@Getter
@Builder
public class AudioResponse {

    private final String              action;
    private final String              status;      // "ok" | "error" | "timeout"
    private final Map<String, Object> data;        // campos de la respuesta (sin action/status)
    private final String              rawPayload;  // JSON original de Python

    public boolean isOk() {
        return "ok".equals(status);
    }

    // ── Factory methods ───────────────────────────────────────────────────────

    public static AudioResponse error(String action, String reason) {
        return AudioResponse.builder()
                .action(action)
                .status("error")
                .data(Map.of("reason", reason != null ? reason : "unknown"))
                .build();
    }

    /**
     * Parsea el payload JSON de Python en un AudioResponse.
     * Extrae "action" y "status" del mapa raíz; el resto queda en data.
     */
    @SuppressWarnings("unchecked")
    public static AudioResponse parse(String payload, ObjectMapper om) throws Exception {
        Map<String, Object> map = om.readValue(payload, Map.class);

        String action = (String) map.remove("action");
        // Python incluye "status" en reload_config/learn_apply — ausente en status/learn_ready
        String status = map.containsKey("status") ? String.valueOf(map.remove("status")) : "ok";

        return AudioResponse.builder()
                .action(action)
                .status(status)
                .data(map)
                .rawPayload(payload)
                .build();
    }
}

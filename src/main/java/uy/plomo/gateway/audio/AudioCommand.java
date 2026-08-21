package uy.plomo.gateway.audio;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Comando hacia el inference service Python.
 *
 * Se serializa como JSON plano — action + params al mismo nivel —
 * porque Python no usa un campo "params" anidado:
 *   {"action":"learn","camera_id":"cam_01","event_name":"ALARM_AUTO","duration":30}
 *
 * Coherente con el envelope cloud: {"path":"...", "command":"..."}.
 * AudioCommandService es el equivalente local de MqttDispatcher para el broker local.
 */
@Getter
@Builder
public class AudioCommand {

    private final String              action;
    private final Map<String, Object> params;  // se fusionan al nivel raíz al serializar

    /** Comando sin parámetros (ej: status, reload_config). */
    public static AudioCommand of(String action) {
        return AudioCommand.builder().action(action).build();
    }

    /**
     * Serializa a JSON plano: action + params al mismo nivel.
     * Preserva insertion order para logs legibles.
     */
    public String toJson(ObjectMapper om) throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("action", action);
        if (params != null) map.putAll(params);
        return om.writeValueAsString(map);
    }
}

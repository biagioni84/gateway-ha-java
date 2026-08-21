package uy.plomo.gateway.audio;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST endpoints para comandos al inference service Python.
 *
 * Usados por:
 *   - Tests manuales (curl/Swagger)
 *   - MqttDispatcher cuando el cloud envía comandos de audio vía MQTT
 *
 * Todos los endpoints bloquean hasta recibir respuesta de Python o timeout.
 * Para learn (operación larga) considerar 202 Async en el futuro — Clase 14.
 *
 * Habilitado junto con audio.mqtt.enabled=true.
 */
@RestController
@RequestMapping("/audio")
@ConditionalOnProperty(name = "audio.mqtt.enabled", havingValue = "true")
@Slf4j
@RequiredArgsConstructor
public class AudioCommandController {

    private final AudioCommandService commandService;

    /**
     * GET /audio/status
     * Retorna el estado actual del inference service: uptime, streams, métricas.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        AudioResponse response = commandService.sendStatus();
        return toResponseEntity(response);
    }

    /**
     * POST /audio/reload
     * Recarga events.yaml en Python sin reiniciar el servicio.
     */
    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> reload() {
        AudioResponse response = commandService.sendReloadConfig();
        return toResponseEntity(response);
    }

    /**
     * POST /audio/learn
     * Inicia grabación en vivo para aprender un nuevo evento de audio.
     * Body: {"camera_id":"cam_01","event_name":"ALARM_AUTO","duration":30}
     */
    @PostMapping("/learn")
    public ResponseEntity<Map<String, Object>> learn(@RequestBody Map<String, Object> body) {
        String cameraId   = (String)  body.get("camera_id");
        String eventName  = (String)  body.get("event_name");
        int    duration   = ((Number) body.getOrDefault("duration", 30)).intValue();

        AudioResponse response = commandService.sendLearn(cameraId, eventName, duration);
        return toResponseEntity(response);
    }

    /**
     * POST /audio/learn-apply
     * Aplica un entry events.yaml generado por el cloud a Python.
     * Body: {"event_name":"ALARM_AUTO","entry":{...}}
     */
    /**
     * POST /audio/learn-apply
     * Aplica el entry events.yaml generado por el cloud.
     * Body: {"learn_id":"uuid","event_name":"ALARM_AUTO","entry":{...}}
     * learn_id es el UUID retornado por /audio/learn — requerido para correlación.
     */
    @PostMapping("/learn-apply")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> learnApply(@RequestBody Map<String, Object> body) {
        String learnId              = (String)              body.get("learn_id");
        String eventName            = (String)              body.get("event_name");
        Map<String, Object> entry   = (Map<String, Object>) body.get("entry");

        AudioResponse response = commandService.sendLearnApply(learnId, eventName, entry);
        return toResponseEntity(response);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> toResponseEntity(AudioResponse response) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("action", response.getAction());
        body.put("status", response.getStatus());
        if (response.getData() != null) body.putAll(response.getData());

        int httpStatus = response.isOk() ? 200
                       : "timeout".equals(response.getStatus()) ? 504
                       : 502;
        return ResponseEntity.status(httpStatus).body(body);
    }
}

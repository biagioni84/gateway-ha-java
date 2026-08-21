package uy.plomo.gateway.audio;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import uy.plomo.gateway.device.Device;
import uy.plomo.gateway.device.DeviceService;
import uy.plomo.gateway.mqtt.MqttService;
import uy.plomo.gateway.telemetry.TelemetryBuffer;

import java.util.*;

/**
 * Procesa eventos de audio recibidos del inference service Python.
 *
 * Responsabilidades:
 *   1. Idempotencia por event_id — descarta duplicados en ventana de 5 min
 *   2. Mapeo camera_id → Device — busca por atributo audio.camera_id
 *   3. Forward al TelemetryBuffer — batch flush a AWS IoT Core cada 60s
 *   4. Alerta inmediata para eventos críticos — publica directamente a AWS IoT
 *      sin esperar el flush del buffer (Clase 15)
 */
@Service
@Slf4j
public class AudioEventService {

    private static final long DEDUP_TTL_MS = 5 * 60 * 1_000L; // 5 min
    private static final int  DEDUP_MAX    = 1_000;

    private final DeviceService   deviceService;
    private final TelemetryBuffer telemetryBuffer;

    /**
     * Tipos de evento que disparan alerta inmediata si confidence_level=HIGH.
     * Configurado en application.properties: audio.alert.critical-types
     * Spring convierte la lista separada por comas a Set<String> automáticamente.
     */
    @Value("${audio.alert.critical-types:}")
    private Set<String> criticalTypes;

    /**
     * Canal de publicación directa a AWS IoT.
     * Opcional — null si el gateway no está provisionado o MQTT cloud deshabilitado.
     * publishEvent() ya maneja el caso de no conectado con WARN y return.
     */
    @Autowired(required = false)
    private MqttService mqttService;

    public AudioEventService(DeviceService deviceService, TelemetryBuffer telemetryBuffer) {
        this.deviceService   = deviceService;
        this.telemetryBuffer = telemetryBuffer;
    }

    /**
     * Cache de idempotencia: event_id → timestamp de primera recepción.
     * LinkedHashMap en insertion order — el eldest entry es el más antiguo.
     * Eviction por tamaño (>1000) y por TTL (>5 min).
     */
    private final Map<String, Long> seen = Collections.synchronizedMap(
            new LinkedHashMap<>(DEDUP_MAX, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > DEDUP_MAX ||
                           (System.currentTimeMillis() - eldest.getValue()) > DEDUP_TTL_MS;
                }
            }
    );

    // ── Entry point ───────────────────────────────────────────────────────────

    public void handle(AudioEvent event) {
        if (event.getEventId() != null && isDuplicate(event.getEventId())) {
            log.debug("Audio: duplicate event_id {} — discarding", event.getEventId());
            return;
        }

        log.info("Audio event: type={} camera={} confidence={} level={}",
                event.getType(), event.getCameraId(),
                event.getConfidence(), event.getConfidenceLevel());
        log.debug("Audio event detail: id={} classes={}",
                event.getEventId(), event.getMatchedClasses());

        String deviceId = resolveDeviceId(event.getCameraId());

        Map<String, Object> telEvent = new LinkedHashMap<>();
        telEvent.put("type",             "audio");
        telEvent.put("event",            event.getType());
        telEvent.put("camera_id",        event.getCameraId());
        telEvent.put("device_id",        deviceId);
        telEvent.put("confidence",       event.getConfidence());
        telEvent.put("confidence_level", event.getConfidenceLevel());
        telEvent.put("timestamp",        event.getTimestamp());
        telEvent.put("matched_classes",  event.getMatchedClasses());

        // Path 1: buffer (siempre) — batch flush cada 60s al cloud
        telemetryBuffer.add(telEvent);
        log.debug("Audio event queued for telemetry: type={} device_id={}", event.getType(), deviceId);

        // Path 2: alerta inmediata (solo críticos) — publica sin esperar el flush
        if (isCritical(event)) {
            publishAlert(event, deviceId);
        }
    }

    // ── Alert path ────────────────────────────────────────────────────────────

    /**
     * Retorna true si el evento califica para alerta inmediata:
     *   - confidence_level == HIGH
     *   - type en la lista audio.alert.critical-types
     */
    private boolean isCritical(AudioEvent event) {
        return "HIGH".equals(event.getConfidenceLevel())
                && criticalTypes != null
                && criticalTypes.contains(event.getType());
    }

    /**
     * Publica el evento directamente a AWS IoT sin pasar por el buffer.
     * type="audio_alert" permite al cloud distinguirlo de la telemetría histórica.
     * Si mqttService es null o no está conectado, loguea WARN y retorna sin error.
     */
    private void publishAlert(AudioEvent event, String deviceId) {
        if (mqttService == null) {
            log.warn("Audio alert: MqttService not available — dropping immediate alert for {}",
                    event.getType());
            return;
        }

        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("type",             "audio_alert");
        alert.put("event",            event.getType());
        alert.put("camera_id",        event.getCameraId());
        alert.put("device_id",        deviceId);
        alert.put("confidence",       event.getConfidence());
        alert.put("confidence_level", event.getConfidenceLevel());
        alert.put("timestamp",        event.getTimestamp());
        alert.put("matched_classes",  event.getMatchedClasses());
        alert.put("event_id",         event.getEventId());

        mqttService.publishEvent(alert);
        log.info("Audio alert: immediate publish type={} device_id={}", event.getType(), deviceId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Retorna true si el event_id ya fue procesado en los últimos 5 min.
     * El bloque synchronized cubre containsKey + put como unidad atómica.
     */
    private boolean isDuplicate(String eventId) {
        synchronized (seen) {
            if (seen.containsKey(eventId)) return true;
            seen.put(eventId, System.currentTimeMillis());
            return false;
        }
    }

    /**
     * Busca el Device cuyo atributo audio.camera_id coincide con el camera_id de Python.
     * Retorna null si no hay mapeo configurado — el evento se forwardea igual.
     */
    private String resolveDeviceId(String cameraId) {
        if (cameraId == null) return null;
        return deviceService.findByProtocol("camera").stream()
                .filter(dev -> cameraId.equals(dev.getAttribute("audio", "camera_id")))
                .findFirst()
                .map(Device::getId)
                .orElseGet(() -> {
                    log.warn("Audio: no Device with audio.camera_id='{}' — forwarding without device_id",
                            cameraId);
                    return null;
                });
    }
}

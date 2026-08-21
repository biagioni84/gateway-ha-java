package uy.plomo.gateway.audio;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;

/**
 * Evento de audio publicado por el inference service Python.
 *
 * Contrato: integracion.md — Payload de eventos.
 * Python ya aplicó threshold y deduplicación (EventSuppressor).
 * Java no re-evalúa scores — usa type y confidence_level directamente.
 */
@Data
public class AudioEvent {

    @JsonProperty("event_id")
    private String eventId;

    private String type;

    @JsonProperty("camera_id")
    private String cameraId;

    private String location;
    private String timestamp;
    private double confidence;

    @JsonProperty("confidence_level")
    private String confidenceLevel;

    @JsonProperty("duration_ms")
    private int durationMs;

    @JsonProperty("matched_classes")
    private Map<String, Double> matchedClasses;
}

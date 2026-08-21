package uy.plomo.gateway.telemetry;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uy.plomo.gateway.mqtt.MqttService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Accumulates telemetry events from all protocols and flushes them to MQTT
 * as a single batch on a configurable interval.
 *
 * Events are added concurrently from multiple threads (Z-Wave reader, Zigbee reader,
 * Matter WebSocket thread). The flush drains the queue atomically.
 *
 * Publish topic: iot/v1/{name}/telemetry/{timestamp}
 * Payload: { "events": [ {...}, ... ] }
 *
 * Configured by: telemetry.flush.interval.seconds (default 60)
 * Bounded at MAX_EVENTS entries — oldest are dropped if the queue fills before a flush.
 */
@Component
@Slf4j
public class TelemetryBuffer {

    private static final int MAX_EVENTS = 500;

    private final MqttService mqttService;

    @Value("${telemetry.flush.interval.seconds:60}")
    private int flushIntervalSeconds;

    private final ConcurrentLinkedQueue<Map<String, Object>> queue = new ConcurrentLinkedQueue<>();

    public TelemetryBuffer(MqttService mqttService) {
        this.mqttService = mqttService;
    }

    /**
     * Enqueue a telemetry event. Called from report handlers on any thread.
     * Drops the oldest entry if the buffer is already at MAX_EVENTS.
     */
    public void add(Map<String, Object> event) {
        if (queue.size() >= MAX_EVENTS) {
            queue.poll(); // drop oldest
            log.warn("TelemetryBuffer full ({}) — dropped oldest event", MAX_EVENTS);
        }
        queue.add(event);
    }

    /**
     * Drain the buffer and publish all accumulated events as a single MQTT message.
     * Runs every {@code telemetry.flush.interval.seconds} seconds.
     * No-op if the buffer is empty.
     */
    @Scheduled(fixedDelayString = "${telemetry.flush.interval.seconds:60}000")
    public void flush() {
        if (queue.isEmpty()) return;

        List<Map<String, Object>> batch = new ArrayList<>();
        Map<String, Object> event;
        while ((event = queue.poll()) != null) {
            batch.add(event);
        }

        log.debug("TelemetryBuffer: flushing {} events", batch.size());
        mqttService.publishTelemetry(batch);
    }
}

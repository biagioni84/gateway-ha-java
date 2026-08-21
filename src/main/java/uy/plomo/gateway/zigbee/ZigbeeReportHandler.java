package uy.plomo.gateway.zigbee;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import uy.plomo.gateway.device.Device;
import uy.plomo.gateway.device.DeviceService;
import uy.plomo.gateway.diagnostics.UnhandledFrameStore;
import uy.plomo.gateway.mqtt.MqttService;
import uy.plomo.gateway.telemetry.TelemetryBuffer;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Processes unsolicited Zigbee frames from the Z3Gateway.
 *
 * Mirrors handle-report-attribute, handle-lock-operation,
 * handle-pincode-response, handle-weekday-schedule-response
 * from the legacy Clojure implementation.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ZigbeeReportHandler {

    private final DeviceService       deviceService;
    private final TelemetryBuffer     telemetryBuffer;
    private final UnhandledFrameStore unhandledFrameStore;
    @Lazy private final MqttService       mqttService;
    @Lazy private final ZigbeeController  zigbeeController;

    // ── Entry point ───────────────────────────────────────────────────────────

    /**
     * Process a parsed Zigbee message received from the Z3Gateway.
     * Called by ZigbeeInterface for every recognized output line.
     */
    public void handleMessage(ZigbeeMessage msg) {
        if (msg == null) return;

        switch (msg.getType()) {
            case "frame"              -> handleFrame(msg);
            case "trust_center"       -> handleTrustCenter(msg);
            case "inclusion_started"  -> log.info("Zigbee inclusion opened ({}s)", msg.getRawData());
            case "inclusion_finished" -> log.info("Zigbee inclusion closed");
            default                   -> {} // ignored
        }
    }

    // ── Frame dispatch ────────────────────────────────────────────────────────

    private void handleFrame(ZigbeeMessage msg) {
        String node    = msg.getNode();
        String cluster = msg.getCluster();
        String command = msg.getCommand();
        log.debug("Zigbee frame: node={} cluster={} cmd={}", node, cluster, command);

        // If device woke up but has no descriptor yet, attempt a re-interview
        deviceService.findByNode(node).ifPresent(dev -> {
            if ((dev.getDescriptor() == null || "unknown".equals(dev.getDescriptor()))
                    && dev.getIeeeAddr() != null) {
                log.debug("Frame from unidentified device {} — triggering re-interview", node);
                CompletableFuture.runAsync(() -> {
                    try { zigbeeController.interview(dev.getIeeeAddr()); }
                    catch (Exception e) { log.warn("Wake-up re-interview failed for {}", node, e); }
                });
            }
        });

        // Forward event if device has fwdEvents configured
        forwardEventIfConfigured(node, command, msg);

        switch (command) {
            case "ReportAttributes"           -> handleReportAttributes(msg);
            case "GetPINCodeResponse"         -> handlePincodeResponse(msg);
            case "OperationEventNotification" -> handleOperationEvent(msg);
            case "GetWeekdayScheduleResponse" -> handleWeekdayScheduleResponse(msg);
            default -> unhandledFrameStore.record("zigbee", node, cluster, command, null);
        }
    }

    // ── Individual handlers ───────────────────────────────────────────────────

    private void handleReportAttributes(ZigbeeMessage msg) {
        String node    = msg.getNode();
        String cluster = msg.getCluster();
        String raw     = msg.field("raw");
        if (raw == null) return;

        List<String> bytes = Arrays.asList(raw.split("\\s+"));
        if (bytes.isEmpty()) return;

        deviceService.findByNode(node).ifPresentOrElse(dev -> {
            switch (cluster != null ? cluster : "") {
                case "DoorLock"            -> handleDoorLockReport(dev, bytes);
                case "PowerConfiguration"  -> handleBatteryReport(dev, bytes);
                default -> unhandledFrameStore.record("zigbee", node, cluster, "ReportAttributes", null);
            }
            deviceService.save(dev);
        }, () -> log.debug("ReportAttributes: no device for node {}", node));
    }

    /** ZCL DoorLock ReportAttributes — LockState attribute 0x0000: bytes "00 00 30 XX". */
    private void handleDoorLockReport(Device dev, List<String> bytes) {
        if (bytes.size() >= 4 && "00".equalsIgnoreCase(bytes.get(0)) && "00".equalsIgnoreCase(bytes.get(1))) {
            String status = switch (bytes.get(3).toUpperCase()) {
                case "01" -> "locked";
                case "02" -> "unlocked";
                default   -> "unknown";
            };
            dev.setAttribute("status", "value", status);
            dev.setAttribute("status", "time", Instant.now().toString());
        }
    }

    /**
     * ZCL PowerConfiguration ReportAttributes.
     * BatteryPercentageRemaining 0x0021: bytes "21 00 20 XX" — value is XX/2 percent (ZCL range 0–200).
     */
    private void handleBatteryReport(Device dev, List<String> bytes) {
        if (bytes.size() >= 4 && "21".equalsIgnoreCase(bytes.get(0)) && "00".equalsIgnoreCase(bytes.get(1))) {
            int pct = ZigbeeProtocol.parseHex(bytes.get(3)) / 2;
            dev.setAttribute("battery", "value", pct);
            dev.setAttribute("battery", "time", Instant.now().toString());
            log.debug("Battery update for {}: {}%", dev.getNode(), pct);
        }
    }

    private void handlePincodeResponse(ZigbeeMessage msg) {
        String node   = msg.getNode();
        String userId = msg.field("UserID");
        String status = msg.field("UserStatus");
        String code   = msg.field("Code");
        if (userId == null) return;

        deviceService.findByNode(node).ifPresent(dev -> {
            if ("Available".equalsIgnoreCase(status) || "00".equalsIgnoreCase(status)) {
                if (dev.getPincodes() != null) dev.getPincodes().remove(userId);
            } else {
                if (dev.getPincodes() == null) dev.setPincodes(new HashMap<>());
                dev.getPincodes().put(userId, code != null ? code : "");
            }
            deviceService.save(dev);
            log.debug("Updated pincode slot {} for node {}", userId, node);
        });
    }

    private void handleOperationEvent(ZigbeeMessage msg) {
        String node      = msg.getNode();
        String eventCode = msg.field("OperationEventCode");
        if (eventCode == null) return;

        String status = null;
        int code = ZigbeeProtocol.parseHex(eventCode);
        // ZCL DoorLock OperationEventCode: even = lock, odd = unlock (simplified)
        // Typical: 01=Lock, 02=Unlock, 03=LockFailureInvalidPIN, 06=UnlockInvalidSchedule, etc.
        if (code == 1 || code == 3 || code == 5 || code == 9 || code == 10 || code == 11) {
            status = "locked";
        } else if (code == 2 || code == 4 || code == 6 || code == 7 || code == 8) {
            status = "unlocked";
        }

        if (status != null) {
            String finalStatus = status;
            deviceService.findByNode(node).ifPresent(dev -> {
                dev.setAttribute("status", "value", finalStatus);
                dev.setAttribute("status", "time", Instant.now().toString());
                deviceService.save(dev);
            });
        }
    }

    private void handleWeekdayScheduleResponse(ZigbeeMessage msg) {
        String node   = msg.getNode();
        String userId = msg.field("UserID");
        String status = msg.field("Status");
        if (userId == null) return;

        deviceService.findByNode(node).ifPresent(dev -> {
            if ("00".equals(status)) { // SUCCESS
                String dayMask = msg.field("DaysMask");
                String startH  = msg.field("StartHour");
                String startM  = msg.field("StartMinute");
                String endH    = msg.field("EndHour");
                String endM    = msg.field("EndMinute");
                String start = String.format("%s:%s",
                        formatHex2(startH), formatHex2(startM));
                String end   = String.format("%s:%s",
                        formatHex2(endH),   formatHex2(endM));
                dev.setAttribute("restriction_" + userId, "type",    "WeekDayScheduleUser");
                dev.setAttribute("restriction_" + userId, "dayMask", dayMask);
                dev.setAttribute("restriction_" + userId, "start",   start);
                dev.setAttribute("restriction_" + userId, "end",     end);
                dev.setAttribute("restriction_" + userId, "time",    Instant.now().toString());
            } else {
                dev.setAttribute("restriction_" + userId, "type", null);
            }
            deviceService.save(dev);
        });
    }

    private void handleTrustCenter(ZigbeeMessage msg) {
        String status = msg.field("status");
        String node   = msg.getNode();
        if ("device left".equalsIgnoreCase(status)) {
            log.info("Zigbee device left: {}", node);
        } else if (status != null && status.contains("join")) {
            log.info("Zigbee device joined: {} — starting interview", node);
            CompletableFuture.runAsync(() -> {
                try {
                    zigbeeController.interview(node);
                } catch (Exception e) {
                    log.error("Auto-interview failed for node {}", node, e);
                }
            });
        }
    }

    // ── Event forwarding ──────────────────────────────────────────────────────

    private void forwardEventIfConfigured(String node, String command, ZigbeeMessage msg) {
        deviceService.findByNode(node).ifPresent(dev -> {
            List<String> fwdEvents = dev.getFwdEvents();
            if (fwdEvents != null && fwdEvents.contains(command)) {
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("type",    "zigbee");
                event.put("node-id", node);
                event.put("payload", Map.of("cmd", command, "fields", msg.getFields()));
                telemetryBuffer.add(event);
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Format hex string as 2-digit decimal, e.g. "0b" → "11". */
    private String formatHex2(String hex) {
        if (hex == null) return "00";
        try { return String.format("%02d", ZigbeeProtocol.parseHex(hex)); }
        catch (Exception e) { return "00"; }
    }
}

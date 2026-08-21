package uy.plomo.gateway.zwave;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import uy.plomo.gateway.descriptor.DeviceDescriptorService;
import uy.plomo.gateway.descriptor.ResolvedDescriptor;
import uy.plomo.gateway.descriptor.ZWaveDescriptor;
import uy.plomo.gateway.device.Device;
import uy.plomo.gateway.device.DeviceService;
import uy.plomo.gateway.diagnostics.UnhandledFrameStore;
import uy.plomo.gateway.mqtt.MqttService;
import uy.plomo.gateway.telemetry.TelemetryBuffer;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Processes Z-Wave report frames received from zipgateway.
 *
 * Mirrors default-report-handler + handle-report + handle-frame in zwave/interface.clj.
 *
 * For each incoming frame (parsed by ZWaveProtocol.parseFrame as Map<String,Object>):
 *   1. Identify the device by node ID
 *   2. Update device state in the database
 *   3. Forward events to MQTT if the device has fwdEvents configured
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ZWaveReportHandler {

    private final DeviceService           deviceService;
    private final DeviceDescriptorService descriptorService;
    private final ObjectMapper            objectMapper;
    private final TelemetryBuffer         telemetryBuffer;
    private final UnhandledFrameStore     unhandledFrameStore;
    @Lazy private final MqttService       mqttService;
    @Lazy private final ZWaveController   zwaveController;

    // ── Entry point ───────────────────────────────────────────────────────────

    /**
     * Process a decoded Z/IP packet received from a Z-Wave node.
     * Mirrors (handle-frame src frame) in zwave/interface.clj.
     *
     * @param packet  result of ZWaveProtocol.parseFrame — Map with "class","command","data" keys
     */
    public void handleFrame(int srcNodeId, Map<String, Object> packet) {
        Map<String, Object> zwaveCmd = ZWaveController.getZwaveCmd(packet);
        if (zwaveCmd == null) {
            // ZIP-level frames (ACK/NACK) have no Z-Wave command payload.
            // Log delayed NACK_RESPONSE so delivery failures are visible.
            Object data = packet.get("data");
            if (data instanceof Map<?, ?> dataMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> props1 = ZWaveInterface.getProperties1((Map<String, Object>) dataMap);
                if (props1 != null && Boolean.TRUE.equals(props1.get("NACK_RESPONSE_BIT_MASK_V2"))) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> header = ZWaveInterface.getHeader((Map<String, Object>) dataMap);
                    Object seqnum = header != null ? header.get("seqnum") : "?";
                    log.warn("Z-Wave: delivery FAILED — NACK_RESPONSE from controller for seq={} " +
                             "(FLiRS device did not respond to beam; check device range/battery)", seqnum);
                }
            }
            return;
        }

        String commandClass = strOf(zwaveCmd.get("class"));
        String command      = strOf(zwaveCmd.get("command"));
        Map<String, Object> fields = getFields(zwaveCmd);

        String nodeHex = String.format("0x%02X", srcNodeId);
        log.debug("handleFrame node={}{} class={} cmd={}", nodeHex,
                srcNodeId == 1 ? " (controller)" : "", commandClass, command);

        // Single DB lookup per frame — reused by both event forwarding and report handling.
        Optional<Device> devOpt = deviceService.findByNode(nodeHex);

        forwardEventIfConfigured(devOpt, nodeHex, command, fields);

        if (!command.contains("_REPORT") && !command.contains("NODE_LIST")
                && !"WAKE_UP_NOTIFICATION".equals(command)) {
            unhandledFrameStore.record("zwave", nodeHex, commandClass, command, fields);
            return;
        }

        try {
            handleReport(srcNodeId, nodeHex, commandClass, command, fields, devOpt);
        } catch (Exception e) {
            log.error("Error handling report {} from node {}", command, nodeHex, e);
        }
    }

    // ── Report dispatch ───────────────────────────────────────────────────────

    private void handleReport(int srcNodeId, String nodeHex, String commandClass, String command,
                              Map<String, Object> fields, Optional<Device> devOpt) {
        if ("NODE_LIST_REPORT".equals(command) || "COMMAND_NODE_LIST_REPORT".equals(command)) {
            handleNodeListReport(fields);
            return;
        }

        // NODE_INFO_CACHED_REPORT arrives from the controller (srcNodeId=1); the
        // actual target node ID is inside the payload, so we handle it separately.
        if ("NODE_INFO_CACHED_REPORT".equals(command) || "COMMAND_NODE_INFO_CACHED_REPORT".equals(command)) {
            handleNodeInfoCachedReport(fields);
            return;
        }

        // WAKE_UP_NOTIFICATION uses the actual srcNodeId.
        if ("WAKE_UP_NOTIFICATION".equals(command)) {
            handleWakeUpNotification(srcNodeId, nodeHex, devOpt);
            return;
        }

        if (devOpt.isEmpty()) {
            log.warn("handleReport: no device for node {}", nodeHex);
            return;
        }

        Device dev = devOpt.get();
        String now = Instant.now().toString();

        switch (command) {
            case "MANUFACTURER_SPECIFIC_REPORT"  -> {
                // applyDescriptor saves when a descriptor is found; only save here when it wasn't.
                if (!handleManufacturerSpecificReport(dev, fields, now)) deviceService.save(dev);
                return;
            }
            case "DOOR_LOCK_OPERATION_REPORT"    -> handleDoorLockReport(dev, fields, now);
            case "NOTIFICATION_REPORT",
                 "ALARM_REPORT"                  -> handleNotificationReport(dev, fields, now);
            case "USER_CODE_REPORT"              -> handleUserCodeReport(dev, fields, now);
            case "BATTERY_REPORT"                -> handleBatteryReport(dev, fields, now);
            case "BASIC_REPORT"                  -> handleBasicReport(dev, fields, now);
            case "SWITCH_BINARY_REPORT"          -> handleSwitchBinaryReport(dev, fields, now);
            case "SWITCH_MULTILEVEL_REPORT"      -> handleSwitchMultilevelReport(dev, fields, now);
            case "THERMOSTAT_SETPOINT_REPORT"    -> handleThermostatSetpointReport(dev, fields, now);
            case "THERMOSTAT_MODE_REPORT"        -> handleThermostatModeReport(dev, fields, now);
            case "THERMOSTAT_FAN_MODE_REPORT"    -> handleThermostatFanModeReport(dev, fields, now);
            case "SENSOR_MULTILEVEL_REPORT"      -> handleSensorMultilevelReport(dev, fields, now);
            case "SENSOR_BINARY_REPORT"          -> handleSensorBinaryReport(dev, fields, now);
            case "SCHEDULE_ENTRY_LOCK_YEAR_DAY_REPORT"
                                                 -> handleYearDayReport(dev, fields, now);
            case "SCHEDULE_ENTRY_LOCK_DAILY_REPEATING_REPORT"
                                                 -> handleDailyRepeatingReport(dev, fields, now);
            default                              -> handleGenericReport(dev, commandClass, command, fields, now);
        }

        deviceService.save(dev);
    }

    // ── Individual report handlers ────────────────────────────────────────────

    private void handleDoorLockReport(Device dev, Map<String, Object> fields, String now) {
        String mode = field(fields, "currentDoorLockMode");
        String status = switch (mode != null ? mode : "") {
            case "ff", "FF" -> "locked";
            case "00"       -> "unlocked";
            default         -> "unknown";
        };
        dev.setAttribute("status", "value", status);
        dev.setAttribute("status", "time",  now);
    }

    private void handleNotificationReport(Device dev, Map<String, Object> fields, String now) {
        String alarmType = field(fields, "alarm-type");
        if (alarmType == null) alarmType = field(fields, "zwaveAlarmType");

        if (alarmType != null) {
            String status = switch (alarmType) {
                case "12", "15", "18" -> "locked";
                case "13", "16", "19" -> "unlocked";
                default               -> null;
            };
            if (status != null) {
                dev.setAttribute("status", "value", status);
                dev.setAttribute("status", "time",  now);
            }
        }
        dev.setAttribute("NOTIFICATION_REPORT", alarmType != null ? alarmType : "unknown",
                payloadToMap(fields, now));
    }

    private void handleUserCodeReport(Device dev, Map<String, Object> fields, String now) {
        String userId = field(fields, "usr-id");
        String status = field(fields, "usr-status");
        String code   = field(fields, "code");
        if (userId == null) return;

        if ("00".equals(status)) {
            if (dev.getPincodes() != null) dev.getPincodes().remove(userId);
        } else {
            String statusMsg = switch (status != null ? status.toUpperCase() : "") {
                case "01" -> "occupied";
                case "02" -> "reserved";
                case "FE" -> "status not available";
                default   -> "unknown status";
            };
            if (dev.getPincodes() == null) dev.setPincodes(new HashMap<>());
            dev.getPincodes().put(userId, code != null ? code : "");
            dev.setAttribute("pincode_status_" + userId, "status", statusMsg);
            dev.setAttribute("pincode_status_" + userId, "time",   now);
        }
    }

    private void handleBatteryReport(Device dev, Map<String, Object> fields, String now) {
        String val = field(fields, "batteryLevel");
        int level = val != null ? parseHex(val) : -1;
        dev.setAttribute("battery", "value", level);
        dev.setAttribute("battery", "time",  now);
    }

    private void handleSwitchBinaryReport(Device dev, Map<String, Object> fields, String now) {
        String val = field(fields, "currentValue");
        if (val != null) {
            String status = "00".equalsIgnoreCase(val) ? "off" : "on";
            dev.setAttribute("status", "value", status);
            dev.setAttribute("status", "time",  now);
        }
    }

    private void handleBasicReport(Device dev, Map<String, Object> fields, String now) {
        String val = field(fields, "value");
        if (val != null) {
            String status = "00".equalsIgnoreCase(val) ? "off" : "on";
            dev.setAttribute("status", "value", status);
            dev.setAttribute("status", "time",  now);
        } else {
            dev.setAttribute("BASIC_REPORT", "data", payloadToMap(fields, now));
        }
    }

    private void handleSwitchMultilevelReport(Device dev, Map<String, Object> fields, String now) {
        String val = field(fields, "value");
        int level  = val != null ? parseHex(val) : 0;
        dev.setAttribute("status", "value", level);
        dev.setAttribute("status", "time",  now);
    }

    private void handleThermostatSetpointReport(Device dev, Map<String, Object> fields, String now) {
        String levelField = field(fields, "level");
        String setpointType = switch (levelField != null ? levelField : "") {
            case "01" -> "heat";
            case "02" -> "cool";
            default   -> levelField;
        };
        String val1  = field(fields, "value1");
        int    value = val1 != null ? parseHex(val1) : 0;
        dev.setAttribute("setpoint_" + setpointType, "value", value);
        dev.setAttribute("setpoint_" + setpointType, "time",  now);
    }

    private void handleThermostatModeReport(Device dev, Map<String, Object> fields, String now) {
        String level = field(fields, "level");
        String mode  = level != null ? switch (level.toUpperCase()) {
            case "00" -> "off";
            case "01" -> "heat";
            case "02" -> "cool";
            case "03" -> "auto";
            case "04" -> "aux";
            case "05" -> "resume";
            case "06" -> "fan";
            case "07" -> "furnace";
            case "08" -> "dry";
            case "09" -> "moist";
            case "0A" -> "auto_changeover";
            case "0B" -> "energy_heat";
            default   -> level;
        } : "unknown";
        dev.setAttribute("mode", "value", mode);
        dev.setAttribute("mode", "time",  now);
    }

    private void handleThermostatFanModeReport(Device dev, Map<String, Object> fields, String now) {
        String prop = field(fields, "properties1");
        String fanMode = prop != null ? switch (prop) {
            case "00" -> "auto_low";
            case "01" -> "low";
            case "02" -> "auto_high";
            case "03" -> "high";
            case "04" -> "auto_medium";
            case "05" -> "medium";
            case "06" -> "circulation";
            case "07" -> "humidity";
            case "08" -> "left_right";
            default   -> prop;
        } : "unknown";
        dev.setAttribute("fanmode", "value", fanMode);
        dev.setAttribute("fanmode", "time",  now);
    }

    private void handleSensorMultilevelReport(Device dev, Map<String, Object> fields, String now) {
        String sensorType = field(fields, "sensorType");
        String val1       = field(fields, "sensorValue1");
        int    value      = val1 != null ? parseHex(val1) : 0;
        String key        = sensorType != null ? sensorType.toUpperCase() : "UNKNOWN";
        dev.setAttribute("sensor_" + key, "value", value);
        dev.setAttribute("sensor_" + key, "time",  now);
    }

    private void handleSensorBinaryReport(Device dev, Map<String, Object> fields, String now) {
        String val     = field(fields, "sensorValue");
        boolean active = "ff".equalsIgnoreCase(val);
        dev.setAttribute("status", "value", active);
        dev.setAttribute("status", "time",  now);
    }

    private void handleYearDayReport(Device dev, Map<String, Object> fields, String now) {
        String userId = field(fields, "userIdentifier");
        if (userId == null) return;
        String sY = field(fields, "startYear");
        boolean empty = "ff".equalsIgnoreCase(sY);
        if (!empty) {
            String start = buildDatetime(fields, "start");
            String end   = buildDatetime(fields, "stop");
            dev.setAttribute("restriction_" + userId, "type",  "YearDayScheduleUser");
            dev.setAttribute("restriction_" + userId, "start", start);
            dev.setAttribute("restriction_" + userId, "end",   end);
            dev.setAttribute("restriction_" + userId, "time",  now);
        } else {
            dev.setAttribute("restriction_" + userId, "type", null);
        }
    }

    private void handleDailyRepeatingReport(Device dev, Map<String, Object> fields, String now) {
        String userId = field(fields, "userIdentifier");
        if (userId == null) return;
        String mask = field(fields, "weekDayBitmask");
        boolean empty = "00".equals(mask);
        if (!empty) {
            dev.setAttribute("restriction_" + userId, "type",    "WeekDayScheduleUser");
            dev.setAttribute("restriction_" + userId, "dayMask", mask);
            dev.setAttribute("restriction_" + userId, "time",    now);
        } else {
            dev.setAttribute("restriction_" + userId, "type", null);
        }
    }

    private void handleGenericReport(Device dev, String commandClass, String command,
                                     Map<String, Object> fields, String now) {
        dev.setAttribute(command, "data", payloadToMap(fields, now));
        unhandledFrameStore.record("zwave", dev.getNode(), commandClass, command, fields);
    }

    // ── Node list report ──────────────────────────────────────────────────────

    private void handleNodeListReport(Map<String, Object> fields) {
        log.info("NODE_LIST_REPORT received — fields: {}", fields);

        Object raw = fields.get("list");
        if (raw == null) raw = fields.get("nodes");
        if (raw == null) raw = fields.get("nodeIds");

        if (raw instanceof List<?> nodes) {
            List<Integer> ids = nodes.stream()
                    .filter(Objects::nonNull)
                    .map(o -> {
                        try {
                            return o instanceof Number n ? n.intValue()
                                    : Integer.parseInt(o.toString(), 16);
                        } catch (NumberFormatException e) {
                            return -1;
                        }
                    })
                    .filter(id -> id > 1)
                    .collect(Collectors.toList());

            log.info("NODE_LIST_REPORT: {} non-controller nodes: {}", ids.size(), ids);
            for (int nodeId : ids) {
                try {
                    zwaveController.setup(nodeId);
                } catch (Exception e) {
                    log.warn("setup failed for node {}", nodeId, e);
                }
            }
        } else {
            log.warn("NODE_LIST_REPORT: cannot extract node list (raw={})", raw);
        }
    }

    // ── Interview report handlers ─────────────────────────────────────────────

    /** Returns true if applyDescriptor was called (and saved), false otherwise. */
    private boolean handleManufacturerSpecificReport(Device dev, Map<String, Object> fields, String now) {
        String mfrId       = ZWaveUtils.buildHexId(fields, "manufacturerId1", "manufacturerId2");
        String productTypeId = ZWaveUtils.buildHexId(fields, "productTypeId1", "productTypeId2");
        String modelId     = ZWaveUtils.buildHexId(fields, "productId1", "productId2");
        if (mfrId        != null) dev.setManufacturerId(mfrId);
        if (productTypeId != null) dev.setProductTypeId(productTypeId);
        if (modelId      != null) dev.setModelId(modelId);
        log.debug("MANUFACTURER_SPECIFIC_REPORT node={} mfr={} productType={} model={}",
                dev.getNode(), mfrId, productTypeId, modelId);

        if (mfrId != null) {
            Map<String, String> criteria = new java.util.HashMap<>();
            criteria.put("manufacturerId", mfrId);
            if (productTypeId != null) criteria.put("productTypeId", productTypeId);
            if (modelId       != null) criteria.put("modelId",       modelId);
            Optional<ResolvedDescriptor> resolved = descriptorService.resolve("zwave", criteria);
            if (resolved.isPresent()) {
                applyDescriptor(dev, resolved.get(), "manufacturer");
                return true;
            }
        }
        log.debug("No manufacturer descriptor for node {} (mfr={} productType={} model={})",
                dev.getNode(), mfrId, productTypeId, modelId);
        return false;
    }

    private void handleNodeInfoCachedReport(Map<String, Object> fields) {
        // The REPORT contains no node ID — correlate via the seq-no from the GET.
        String seqNoHex = field(fields, "seq-no");
        if (seqNoHex == null) {
            log.warn("NODE_INFO_CACHED_REPORT missing seq-no field — fields: {}", fields);
            return;
        }
        String nodeHex = zwaveController.claimNodeInfoSeq(parseHex(seqNoHex));
        if (nodeHex == null) {
            log.warn("NODE_INFO_CACHED_REPORT: no pending interview for seq={}", seqNoHex);
            return;
        }
        log.debug("NODE_INFO_CACHED_REPORT (from controller) — target node={} seq={}", nodeHex, seqNoHex);

        Optional<Device> devOpt = deviceService.findByNode(nodeHex);
        if (devOpt.isEmpty()) {
            log.warn("NODE_INFO_CACHED_REPORT: no device for node {}", nodeHex);
            return;
        }
        Device dev = devOpt.get();

        String generic  = ZWaveUtils.getString(fields, "generic");
        String specific = ZWaveUtils.getString(fields, "specific");
        log.info("NODE_INFO_CACHED_REPORT node={} generic={} specific={}", nodeHex, generic, specific);

        // Manufacturer descriptor takes priority — don't override if already set.
        if ("manufacturer".equals(dev.getDescriptorSource())) {
            log.debug("Node {} already has manufacturer descriptor — ignoring node info", nodeHex);
            return;
        }

        if (generic != null && specific != null) {
            Optional<ResolvedDescriptor> resolved = descriptorService.resolve(
                    "zwave", Map.of("generic", generic, "specific", specific));
            if (resolved.isPresent()) {
                applyDescriptor(dev, resolved.get(), "generic");
                return;
            }
        }
        log.debug("No generic descriptor for node {} (generic={} specific={})", nodeHex, generic, specific);
    }

    private void handleWakeUpNotification(int srcNodeId, String nodeHex, Optional<Device> devOpt) {
        log.info("WAKE_UP_NOTIFICATION from node {}", nodeHex);
        if (devOpt.isEmpty()) {
            log.warn("WAKE_UP_NOTIFICATION: no device for node {}", nodeHex);
            return;
        }
        Device dev = devOpt.get();

        if (dev.getDescriptor() == null) {
            // Descriptor not yet resolved — re-trigger interview GETs.
            log.info("Node {} woke up with no descriptor — re-sending interview GETs", nodeHex);
            CompletableFuture.runAsync(() -> zwaveController.interview(srcNodeId));
        } else {
            // Already interviewed — nothing pending, let the node sleep.
            log.debug("Node {} woke up, descriptor already set — sending NO_MORE_INFO", nodeHex);
            zwaveController.wakeUpNoMoreInformation(nodeHex);
        }
    }

    /**
     * Applies a resolved descriptor to the device, stores descriptor_source,
     * and triggers init tasks asynchronously so the wake-up window isn't blocked.
     */
    private void applyDescriptor(Device dev, ResolvedDescriptor resolved, String source) {
        ZWaveDescriptor desc = (ZWaveDescriptor) resolved.content();
        dev.setType(desc.getType());
        dev.setDescriptor(resolved.file());
        dev.setDescriptorSource(source);
        if (dev.getName() == null && desc.getLabel() != null) dev.setName(desc.getLabel());
        if (!desc.getFwdEvents().isEmpty()) dev.setFwdEvents(desc.getFwdEvents());
        deviceService.save(dev);
        log.info("Descriptor resolved for node {} — type={} source={} file={}",
                dev.getNode(), desc.getType(), source, resolved.file());

        zwaveController.markInterviewComplete(dev.getNode());

        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type",    "zwave");
            event.put("node-id", dev.getNode());
            event.put("payload", Map.of(
                    "cmd",        "INTERVIEW_COMPLETE",
                    "descriptor", resolved.file(),
                    "source",     source,
                    "deviceType", desc.getType() != null ? desc.getType() : "unknown"));
            mqttService.publishEvent(objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.warn("Failed to publish INTERVIEW_COMPLETE event for node {}", dev.getNode(), e);
        }

        CompletableFuture.runAsync(() -> {
            try {
                zwaveController.runInitTasks(dev.getNode(), desc, dev);
            } catch (Exception e) {
                log.error("runInitTasks failed for node {}", dev.getNode(), e);
            }
        });
    }

    // ── Event forwarding ──────────────────────────────────────────────────────

    private void forwardEventIfConfigured(Optional<Device> devOpt, String nodeHex,
                                           String command, Map<String, Object> fields) {
        devOpt.ifPresent(dev -> {
            List<String> fwdEvents = dev.getFwdEvents();
            if (fwdEvents != null && fwdEvents.contains(command)) {
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("type",    "zwave");
                event.put("node-id", nodeHex);
                event.put("payload", Map.of("cmd", command, "data", payloadToMap(fields, null)));
                telemetryBuffer.add(event);
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Extract decoded fields map from a Z-Wave command map (the "payload" sub-key). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> getFields(Map<String, Object> zwaveCmd) {
        Object p = zwaveCmd.get("payload");
        return (p instanceof Map) ? (Map<String, Object>) p : Map.of();
    }

    /** Get a field value by name (case-insensitive). */
    private String field(Map<String, Object> fields, String name) {
        if (fields == null) return null;
        Object v = fields.get(name);
        if (v != null) return v.toString();
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name))
                return e.getValue() != null ? e.getValue().toString() : null;
        }
        return null;
    }

    private Map<String, Object> payloadToMap(Map<String, Object> fields, String time) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (fields != null) m.putAll(fields);
        if (time != null) m.put("time", time);
        return m;
    }

    private int parseHex(String hex) {
        try { return Integer.parseUnsignedInt(hex.replace("0x", "").replace("0X", ""), 16); }
        catch (Exception e) { return 0; }
    }

    private String buildDatetime(Map<String, Object> fields, String prefix) {
        int year  = parseHex(field(fields, prefix + "Year"));
        int month = parseHex(field(fields, prefix + "Month"));
        int day   = parseHex(field(fields, prefix + "Day"));
        int hour  = parseHex(field(fields, prefix + "Hour"));
        int min   = parseHex(field(fields, prefix + "Minute"));
        if (month == 0) month = 1;
        if (day   == 0) day   = 1;
        // Z-Wave spec: year field is 1-byte offset from 2000 (e.g. 0x18 → 2024).
        return String.format("20%02d-%02d-%02dT%02d:%02d:00", year, month, day, hour, min);
    }

    private static String strOf(Object o) {
        return o != null ? o.toString() : "UNKNOWN";
    }
}

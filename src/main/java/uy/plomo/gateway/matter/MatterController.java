package uy.plomo.gateway.matter;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uy.plomo.gateway.device.Device;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Matter device type IDs → logical type strings used by the UI.
 * Keys are decimal Matter device type IDs from the Descriptor cluster (N/29/0, field "0").
 */

/**
 * High-level Matter operations.
 *
 * Maps to python-matter-server JSON-RPC commands:
 *   get_nodes, get_node, remove_node, commission_with_code, device_command.
 *
 * For GatewayApiService integration: parseDevice() mirrors ZWaveController.parseDevice().
 * Matter devices store their nodeId in Device.node as a decimal string (e.g. "5").
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MatterController {

    private final MatterInterface matterInterface;

    // ── Node management ───────────────────────────────────────────────────────

    public CompletableFuture<JsonNode> getNodes() {
        return matterInterface.sendCommandWait("get_nodes", null);
    }

    public CompletableFuture<JsonNode> getNode(long nodeId) {
        return matterInterface.sendCommandWait("get_node", Map.of("node_id", nodeId));
    }

    public CompletableFuture<JsonNode> removeNode(long nodeId) {
        return matterInterface.sendCommandWait("remove_node", Map.of("node_id", nodeId));
    }

    // ── Commissioning ─────────────────────────────────────────────────────────

    /**
     * Pair a new device using its manual pairing code or QR string (MT:Y...).
     * Timeout is generous — commissioning can take up to 60 s.
     */
    public CompletableFuture<JsonNode> commissionWithCode(String code) {
        return matterInterface.sendCommandWait("commission_with_code",
                Map.of("code", code), 60_000);
    }

    // ── Device control ────────────────────────────────────────────────────────

    /**
     * Send a Matter cluster command to a node endpoint.
     *
     * @param nodeId      Matter node ID
     * @param endpointId  endpoint on that node (usually 1)
     * @param clusterId   Matter cluster ID (e.g. 6 = OnOff, 8 = LevelControl)
     * @param commandName cluster command name (e.g. "on", "off", "move_to_level")
     * @param commandArgs cluster command arguments, or null for commands with no args
     */
    public CompletableFuture<JsonNode> deviceCommand(
            long nodeId, int endpointId, int clusterId,
            String commandName, Map<String, Object> commandArgs) {

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("node_id",      nodeId);
        args.put("endpoint_id",  endpointId);
        args.put("cluster_id",   clusterId);
        args.put("command_name", commandName);
        args.put("command_args", commandArgs != null ? commandArgs : Map.of());

        return matterInterface.sendCommandWait("device_command", args);
    }

    // ── Convenience helpers ───────────────────────────────────────────────────

    /** Cluster 6 — OnOff */
    public CompletableFuture<JsonNode> turnOn(long nodeId, int endpointId) {
        return deviceCommand(nodeId, endpointId, 6, "on", null);
    }

    public CompletableFuture<JsonNode> turnOff(long nodeId, int endpointId) {
        return deviceCommand(nodeId, endpointId, 6, "off", null);
    }

    public CompletableFuture<JsonNode> toggle(long nodeId, int endpointId) {
        return deviceCommand(nodeId, endpointId, 6, "toggle", null);
    }

    /** Cluster 8 — LevelControl. level: 0–254, transitionTime: tenths of a second. */
    public CompletableFuture<JsonNode> setLevel(long nodeId, int endpointId, int level, int transitionTime) {
        return deviceCommand(nodeId, endpointId, 8, "move_to_level",
                Map.of("level", level, "transition_time", transitionTime));
    }

    // ── Summary view ──────────────────────────────────────────────────────────

    /**
     * Build a flat summary map consistent with ZWaveController/ZigbeeController.parseDevice().
     * Live values (type, status, manufacturer) come from the node cache when available;
     * DB values (set during setup) are the fallback when the node is offline.
     */
    public Map<String, Object> parseDevice(String id, Device dev) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id",       id);
        out.put("protocol", "matter");
        out.put("name",     dev.getName());
        out.put("node",     dev.getNode());

        String nodeStr = dev.getNode();
        if (nodeStr == null) {
            putOffline(out, dev);
            return out;
        }

        try {
            long       nodeId = Long.parseLong(nodeStr);
            MatterNode cached = matterInterface.getNode(nodeId);

            if (cached != null && cached.raw() != null) {
                JsonNode attrs = cached.raw().path("attributes");
                String type    = inferType(attrs);
                out.put("type",           type != null ? type : dev.getType());
                out.put("manufacturer",   textAttr(attrs, "0/40/1"));
                out.put("manufacturerId", vendorIdHex(attrs.path("0/40/2")));
                out.put("modelId",        textAttr(attrs, "0/40/3"));
                out.put("available",      cached.available());
                out.put("status",         inferStatus(attrs, type));
                out.put("battery",        null);
            } else {
                putOffline(out, dev);
            }
        } catch (NumberFormatException e) {
            log.warn("Matter: invalid node '{}' for device {}", nodeStr, id);
            putOffline(out, dev);
        }

        return out;
    }

    private static void putOffline(Map<String, Object> out, Device dev) {
        out.put("type",           dev.getType());
        out.put("manufacturer",   dev.getManufacturer());
        out.put("manufacturerId", dev.getManufacturerId());
        out.put("modelId",        dev.getModelId());
        out.put("available",      false);
        out.put("status",         null);
        out.put("battery",        null);
    }

    // ── Attribute helpers (also used by MatterReportHandler.setup) ────────────

    /**
     * Infer a logical device type by scanning endpoint 1..10 descriptor clusters (N/29/0).
     * Returns the first recognised type, or null if none matched.
     */
    static String inferType(JsonNode attrs) {
        if (attrs == null || attrs.isMissingNode()) return null;
        for (int ep = 1; ep <= 10; ep++) {
            JsonNode dtList = attrs.path(ep + "/29/0");
            if (dtList.isMissingNode() || !dtList.isArray()) continue;
            for (JsonNode entry : dtList) {
                String type = DEVICE_TYPE_MAP.get(entry.path("0").asInt(-1));
                if (type != null) return type;
            }
        }
        return null;
    }

    /**
     * Infer current status from live attributes.
     *   switch / dimmer — OnOff cluster (6) attr 0; dimmer also reads Level cluster (8) attr 0
     *   lock            — DoorLock cluster (257) attr 0: 1=unlocked, 2=locked
     */
    public static Object inferStatus(JsonNode attrs, String type) {
        if (attrs == null || type == null) return null;
        return switch (type) {
            case "switch" -> {
                JsonNode v = attrs.path("1/6/0");
                yield v.isMissingNode() ? null : (v.asBoolean() ? "on" : "off");
            }
            case "dimmer" -> {
                JsonNode onOff = attrs.path("1/6/0");
                if (onOff.isMissingNode()) yield null;
                if (!onOff.asBoolean()) yield "off";
                JsonNode level = attrs.path("1/8/0");
                yield level.isMissingNode() ? "on" : level.asInt();
            }
            case "lock" -> {
                JsonNode v = attrs.path("1/257/0");
                if (v.isMissingNode()) yield null;
                yield switch (v.asInt()) {
                    case 1  -> "unlocked";
                    case 2  -> "locked";
                    default -> "unknown";
                };
            }
            default -> null;
        };
    }

    static String textAttr(JsonNode attrs, String key) {
        JsonNode n = attrs.path(key);
        if (n.isMissingNode() || n.isNull()) return null;
        String v = n.asText(null);
        return (v != null && !v.isBlank()) ? v : null;
    }

    static String vendorIdHex(JsonNode n) {
        if (n.isMissingNode() || n.isNull()) return null;
        int v = n.asInt(-1);
        return v >= 0 ? String.format("0x%04X", v) : null;
    }

    // ── Device type map ───────────────────────────────────────────────────────

    /** Matter device type IDs → logical type. Source: Matter Device Library spec. */
    private static final Map<Integer, String> DEVICE_TYPE_MAP = Map.ofEntries(
        Map.entry(16,   "switch"),           // On/Off Light
        Map.entry(17,   "dimmer"),           // Dimmable Light (legacy)
        Map.entry(257,  "dimmer"),           // Dimmable Light
        Map.entry(259,  "switch"),           // On/Off Light Switch
        Map.entry(260,  "dimmer"),           // Dimmer Switch
        Map.entry(262,  "sensor-occupancy"), // Occupancy Sensor
        Map.entry(263,  "sensor-contact"),   // Contact Sensor (0x0107)
        Map.entry(266,  "switch"),           // On/Off Plug-in Unit
        Map.entry(267,  "dimmer"),           // Dimmable Plug-in Unit
        Map.entry(269,  "dimmer"),           // Extended Color Light
        Map.entry(514,  "lock"),             // Door Lock
        Map.entry(768,  "thermostat"),       // Thermostat
        Map.entry(770,  "sensor-temperature"),// Temperature Sensor
        Map.entry(1026, "sensor-contact"),   // Contact Sensor (0x0402)
        Map.entry(1028, "sensor-occupancy"), // Occupancy Sensor (0x0404)
        Map.entry(1029, "sensor-temperature")// Temperature Sensor (0x0405)
    );
}

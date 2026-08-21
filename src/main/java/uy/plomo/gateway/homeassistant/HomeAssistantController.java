package uy.plomo.gateway.homeassistant;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uy.plomo.gateway.device.Device;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * High-level Home Assistant device operations.
 *
 * Mirrors ZWaveController/ZigbeeController/MatterController for GatewayApiService integration:
 * parseDevice() builds the flat device-summary map, handleDeviceCommand() routes the friendly
 * command verbs used by the existing REST/MQTT API to Home Assistant service calls.
 *
 * Device.node stores the HA entity_id (e.g. "lock.front_door").
 *
 * Pincode management is not yet implemented here — see the LockCodeProvider seam (M4.5).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class HomeAssistantController {

    private final HomeAssistantInterface haInterface;

    // ── Summary view ──────────────────────────────────────────────────────────

    public Map<String, Object> parseDevice(String id, Device dev) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id",       id);
        out.put("protocol", "ha");
        out.put("name",     dev.getName());
        out.put("node",     dev.getNode());

        String entityId = dev.getNode();
        if (entityId == null) {
            putOffline(out, dev);
            return out;
        }

        String domain = domainOf(entityId);
        HAState cached = haInterface.getState(entityId);

        if (cached != null) {
            String type = HomeAssistantTypeMapper.inferType(domain, cached.attributes());
            out.put("type",           type != null ? type : dev.getType());
            out.put("manufacturer",   dev.getManufacturer());
            out.put("manufacturerId", dev.getManufacturerId());
            out.put("modelId",        dev.getModelId());
            boolean available = !"unavailable".equals(cached.state()) && !"unknown".equals(cached.state());
            out.put("available", available);
            out.put("status",    inferStatus(type, cached));
            out.put("battery",   null); // battery lives on a separate HA entity — not resolved here yet
        } else {
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

    private static Object inferStatus(String type, HAState state) {
        if (type == null) return null;
        String raw = state.state();
        if (raw == null) return null;
        return switch (type) {
            case "switch" -> raw; // HA already uses "on"/"off"
            case "dimmer" -> {
                if (!"on".equals(raw)) yield raw;
                JsonNode brightness = state.attributes() != null ? state.attributes().path("brightness") : null;
                if (brightness == null || brightness.isMissingNode() || brightness.isNull()) yield "on";
                yield Math.round(brightness.asInt() / 255.0f * 99); // normalize HA's 0-255 to the existing 0-99 convention
            }
            case "lock"   -> raw; // HA already uses "locked"/"unlocked"/"jammed"/...
            case "camera" -> "unavailable".equals(raw) ? "offline" : "streaming";
            default -> raw;
        };
    }

    // ── Device commands ───────────────────────────────────────────────────────

    public Map<String, Object> handleDeviceCommand(
            Device dev, String cmd, String subId, String method, Map<String, Object> body) {

        String entityId = dev.getNode();
        if (entityId == null) return Map.of("error", "device has no Home Assistant entity id");
        String domain = domainOf(entityId);

        return switch (cmd) {
            case "on"         -> callServiceSync(domain, "turn_on", entityId, null);
            case "off"        -> callServiceSync(domain, "turn_off", entityId, null);
            case "toggle"     -> callServiceSync(domain, "toggle", entityId, null);
            case "switch"     -> handleSwitch(domain, entityId, method, body);
            case "level"      -> handleLevel(entityId, body);
            case "lock"       -> handleLock(entityId, method, body);
            case "thermostat" -> handleThermostat(entityId, body);
            case "pincode"    -> Map.of("error", "pincode management not yet implemented for Home Assistant-backed locks");
            case "service"    -> handleServicePassthrough(entityId, body);
            default -> Map.of("error", "unknown command: " + cmd);
        };
    }

    private Map<String, Object> handleSwitch(String domain, String entityId, String method, Map<String, Object> body) {
        if ("GET".equals(method)) {
            HAState s = haInterface.getState(entityId);
            return Map.of("value", s != null && s.state() != null ? s.state() : "");
        }
        Object value = body != null ? body.get("value") : null;
        boolean on = "on".equals(value) || Boolean.TRUE.equals(value);
        return callServiceSync(domain, on ? "turn_on" : "turn_off", entityId, null);
    }

    private Map<String, Object> handleLevel(String entityId, Map<String, Object> body) {
        Object valueObj = body != null ? body.get("value") : null;
        if (valueObj == null) return Map.of("error", "value is required");
        int pct;
        try {
            pct = (int) Math.round(Double.parseDouble(String.valueOf(valueObj)));
        } catch (NumberFormatException e) {
            return Map.of("error", "value must be numeric");
        }
        pct = Math.max(0, Math.min(99, pct));
        if (pct == 0) return callServiceSync("light", "turn_off", entityId, null);
        return callServiceSync("light", "turn_on", entityId, Map.of("brightness_pct", pct));
    }

    private Map<String, Object> handleLock(String entityId, String method, Map<String, Object> body) {
        if ("GET".equals(method)) {
            HAState s = haInterface.getState(entityId);
            return Map.of("value", s != null && s.state() != null ? s.state() : "");
        }
        Object value = body != null ? body.get("value") : null;
        boolean lock = "lock".equals(value);
        return callServiceSync("lock", lock ? "lock" : "unlock", entityId, null);
    }

    private Map<String, Object> handleThermostat(String entityId, Map<String, Object> body) {
        if (body == null) return Map.of("error", "body required");
        Map<String, Object> data = new LinkedHashMap<>();
        if (body.get("heat") != null) data.put("temperature", body.get("heat"));
        else if (body.get("cool") != null) data.put("temperature", body.get("cool"));

        if (!data.isEmpty()) {
            Map<String, Object> result = callServiceSync("climate", "set_temperature", entityId, data);
            if (result.containsKey("error")) return result;
        }
        if (body.get("mode") != null) {
            return callServiceSync("climate", "set_hvac_mode", entityId, Map.of("hvac_mode", body.get("mode")));
        }
        return Map.of("status", "ok");
    }

    /** Generic escape hatch: {domain, service, data?, entity_id?} — calls any HA service directly. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> handleServicePassthrough(String defaultEntityId, Map<String, Object> body) {
        if (body == null) return Map.of("error", "body required");
        String domain  = str(body, "domain");
        String service = str(body, "service");
        if (domain == null || service == null) return Map.of("error", "domain and service are required");

        Object dataObj = body.get("data");
        Map<String, Object> data = dataObj instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        String entityId = body.get("entity_id") != null ? str(body, "entity_id") : defaultEntityId;
        return callServiceSync(domain, service, entityId, data);
    }

    private Map<String, Object> callServiceSync(String domain, String service, String entityId, Map<String, Object> data) {
        try {
            haInterface.callService(domain, service, entityId, data).orTimeout(10, TimeUnit.SECONDS).join();
            return Map.of("status", "ok");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("Home Assistant: service call {}.{} on {} failed: {}", domain, service, entityId, cause.getMessage());
            return Map.of("error", cause.getMessage() != null ? cause.getMessage() : "service call failed");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String domainOf(String entityId) {
        int dot = entityId.indexOf('.');
        return dot > 0 ? entityId.substring(0, dot) : "";
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body != null ? body.get(key) : null;
        return v != null ? String.valueOf(v) : null;
    }
}

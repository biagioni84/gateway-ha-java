package uy.plomo.gateway.homeassistant;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uy.plomo.gateway.device.Device;
import uy.plomo.gateway.homeassistant.camera.HomeAssistantCameraController;
import uy.plomo.gateway.homeassistant.lock.LockCodeProvider;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * High-level Home Assistant device operations — HAv1 grouped summary + action dispatch.
 *
 * A gateway "device" is now a group of HA entities sharing the same HA device_id (or a
 * group-of-one, for entities with no HA device — helpers, some templates). buildGroupSummary()
 * builds the {status, actions} shape for a group; handleDeviceCommand() resolves an action name
 * against whichever entity in the group actually provides it.
 *
 * Device.node stores the HA entity_id (e.g. "lock.front_door") on each *member* row — the group
 * itself has no row of its own, see GatewayApiService.getSummary()/handleDeviceCommand().
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class HomeAssistantController {

    private final HomeAssistantInterface        haInterface;
    private final LockCodeProvider              lockCodeProvider;
    private final HomeAssistantCameraController cameraController;

    // Entities with no controllable domain contribute this priority tier (== not present in the
    // list). HA itself has no "primary entity" concept for a multi-entity device — this ordering
    // is ours: prefer whatever's actionable, then sensors, most descriptive first.
    private static final List<String> DOMAIN_PRIORITY = List.of(
            "lock", "climate", "switch", "light", "cover", "fan", "camera", "binary_sensor", "sensor");

    // ── Group summary ─────────────────────────────────────────────────────────

    /** Picks the entity that represents the group for id/type/name/available purposes. */
    public Device resolvePrimary(List<Device> members) {
        return members.stream()
                .min(Comparator
                        .comparingInt((Device d) -> domainPriorityIndex(domainOf(d.getNode())))
                        .thenComparingInt(d -> isDiagnosticOrConfig(d) ? 1 : 0)
                        .thenComparing(Device::getNode))
                .orElse(members.get(0));
    }

    public Map<String, Object> buildGroupSummary(String groupId, List<Device> members) {
        Device primary = resolvePrimary(members);
        String primaryEntityId = primary.getNode();
        String primaryDomain = domainOf(primaryEntityId);

        if ("camera".equals(primaryDomain)) {
            // Cameras keep their own existing summary/command handling untouched.
            return parseCameraDevice(groupId, primary);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id",       groupId);
        out.put("protocol", "ha");
        out.put("name",     primary.getName());

        HAState primaryState = haInterface.getState(primaryEntityId);
        String type = primaryState != null
                ? HomeAssistantTypeMapper.inferType(primaryDomain, primaryState.attributes())
                : primary.getType();
        out.put("type",         type != null ? type : primary.getType());
        out.put("manufacturer", primary.getManufacturer());
        out.put("modelId",      primary.getModelId());
        out.put("areaId",       strAttr(primary, "area_id"));
        out.put("areaName",     strAttr(primary, "area_name"));

        boolean available = primaryState != null
                && !"unavailable".equals(primaryState.state()) && !"unknown".equals(primaryState.state());
        out.put("available", available);

        out.put("status",  buildStatus(members));
        out.put("actions", buildActions(members));
        return out;
    }

    /** Exposed for GatewayApiService.handleCameraNetwork()'s per-entity camera listing. */
    public Map<String, Object> parseCameraDevice(String id, Device dev) {
        return cameraController.parseDevice(id, dev);
    }

    private static int domainPriorityIndex(String domain) {
        int idx = DOMAIN_PRIORITY.indexOf(domain);
        return idx >= 0 ? idx : DOMAIN_PRIORITY.size();
    }

    private static boolean isDiagnosticOrConfig(Device d) {
        Object cat = d.getAttribute("_meta", "entity_category");
        return "diagnostic".equals(cat) || "config".equals(cat);
    }

    private static String strAttr(Device d, String key) {
        Object v = d.getAttribute("_meta", key);
        return v != null ? v.toString() : null;
    }

    // ── status: one value per entity in the group, keyed by a short label ──────

    private Map<String, Object> buildStatus(List<Device> members) {
        Map<String, Device> disambiguated = disambiguate(members, Device::getNode, this::statusLabel);
        Map<String, Object> status = new LinkedHashMap<>();
        disambiguated.forEach((label, dev) -> {
            HAState state = haInterface.getState(dev.getNode());
            status.put(label, state != null ? state.state() : null);
        });
        return status;
    }

    /** "sensor-battery" -> "battery"; non-sensor domains use the domain itself ("lock", "climate"). */
    private String statusLabel(Device dev) {
        String domain = domainOf(dev.getNode());
        HAState state = haInterface.getState(dev.getNode());
        String type = state != null ? HomeAssistantTypeMapper.inferType(domain, state.attributes()) : dev.getType();
        if (type != null && type.startsWith("sensor-")) return type.substring("sensor-".length());
        return domain;
    }

    // ── actions: what this group of entities can be told to do ─────────────────

    private record ActionSource(String action, String entityId) {}

    private List<String> buildActions(List<Device> members) {
        List<ActionSource> sources = new ArrayList<>();
        for (Device dev : members) {
            String entityId = dev.getNode();
            String domain = domainOf(entityId);
            HAState state = haInterface.getState(entityId);
            JsonNode attrs = state != null ? state.attributes() : null;
            for (String action : actionsFor(domain, attrs)) {
                sources.add(new ActionSource(action, entityId));
            }
        }
        Map<String, ActionSource> disambiguated =
                disambiguate(sources, ActionSource::entityId, ActionSource::action);
        return new ArrayList<>(disambiguated.keySet());
    }

    /**
     * Candidate actions per domain, gated on real capability attributes where HA exposes one
     * (supported_features bitmasks verified against home-assistant/core source — not guessed):
     *   ClimateEntityFeature: TARGET_TEMPERATURE=1, TARGET_TEMPERATURE_RANGE=2
     *   CoverEntityFeature:   OPEN=1, CLOSE=2, SET_POSITION=4, STOP=8
     *   FanEntityFeature:     SET_SPEED=1, OSCILLATE=2, DIRECTION=4
     * turn_on/turn_off/toggle for switch/light/fan are treated as unconditional (same level of
     * assumption the old on/off/toggle cmds already made) rather than gated on fan's own
     * TURN_ON/TURN_OFF bits — consistent, not a new assumption.
     */
    private static List<String> actionsFor(String domain, JsonNode attrs) {
        return switch (domain) {
            case "lock"   -> List.of("lock", "unlock", "pincode");
            case "switch" -> List.of("turn_on", "turn_off", "toggle");
            case "light"  -> HomeAssistantTypeMapper.isDimmableLight(attrs)
                    ? List.of("turn_on", "turn_off", "toggle", "set_level")
                    : List.of("turn_on", "turn_off", "toggle");
            case "fan"    -> fanActions(attrs);
            case "climate" -> climateActions(attrs);
            case "cover"   -> coverActions(attrs);
            default -> List.of(); // binary_sensor, sensor: read-only; camera handled separately
        };
    }

    private static List<String> fanActions(JsonNode attrs) {
        List<String> actions = new ArrayList<>(List.of("turn_on", "turn_off"));
        int features = supportedFeatures(attrs);
        if ((features & 1) != 0) actions.add("set_speed");     // SET_SPEED
        if ((features & 2) != 0) actions.add("oscillate");     // OSCILLATE
        if ((features & 4) != 0) actions.add("set_direction"); // DIRECTION
        return actions;
    }

    private static List<String> climateActions(JsonNode attrs) {
        List<String> actions = new ArrayList<>();
        int features = supportedFeatures(attrs);
        if ((features & 1) != 0 || (features & 2) != 0) actions.add("set_temperature");
        JsonNode hvacModes = attrs != null ? attrs.path("hvac_modes") : null;
        if (hvacModes != null && hvacModes.isArray() && hvacModes.size() > 1) actions.add("set_hvac_mode");
        return actions;
    }

    private static List<String> coverActions(JsonNode attrs) {
        List<String> actions = new ArrayList<>();
        int features = supportedFeatures(attrs);
        if ((features & 1) != 0) actions.add("open");
        if ((features & 2) != 0) actions.add("close");
        if ((features & 4) != 0) actions.add("set_position");
        if ((features & 8) != 0) actions.add("stop");
        return actions;
    }

    private static int supportedFeatures(JsonNode attrs) {
        return attrs != null ? attrs.path("supported_features").asInt(0) : 0;
    }

    /**
     * Groups items by a base label, disambiguating collisions by sorting the colliding items'
     * entity_id and suffixing _1, _2... in that order — used both for status map keys (two
     * entities of the same kind, e.g. two "occupancy" binary_sensors on one device) and for
     * action-name collisions (two entities in the same group offering the same action).
     */
    private static <T> Map<String, T> disambiguate(
            List<T> items, Function<T, String> entityIdOf, Function<T, String> labelOf) {
        Map<String, List<T>> byLabel = new LinkedHashMap<>();
        for (T item : items) byLabel.computeIfAbsent(labelOf.apply(item), k -> new ArrayList<>()).add(item);

        Map<String, T> result = new LinkedHashMap<>();
        byLabel.forEach((label, group) -> {
            if (group.size() == 1) {
                result.put(label, group.get(0));
                return;
            }
            List<T> sorted = new ArrayList<>(group);
            sorted.sort(Comparator.comparing(entityIdOf));
            for (int i = 0; i < sorted.size(); i++) {
                result.put(label + "_" + (i + 1), sorted.get(i));
            }
        });
        return result;
    }

    // ── Command dispatch: resolve an action against the group's members ─────────

    /**
     * @param groupId the group id from the summary (HA device_id, or a fallback for a
     *                group-of-one) — kept only for error messages here, resolution is by
     *                {@code members}
     * @param members all Device rows in this group
     * @param action  action name from GET /summary's "actions" list for this group
     */
    public Map<String, Object> handleDeviceCommand(
            String groupId, List<Device> members, String action, String subId, String method, Map<String, Object> body) {

        // "service" is a universal escape-hatch, always available, targeting the primary entity
        // unless the body names a different one — matches the old per-entity behavior.
        if ("service".equals(action)) {
            return handleServicePassthrough(resolvePrimary(members).getNode(), body);
        }

        Map<String, String> actionToEntity = resolveActionEntities(members);
        String entityId = actionToEntity.get(action);
        if (entityId == null) {
            return Map.of("error", "unknown action '" + action + "' for device " + groupId);
        }
        String baseAction = stripCollisionSuffix(action);

        return switch (baseAction) {
            case "turn_on"  -> callServiceSync(domainOf(entityId), "turn_on", entityId, null);
            case "turn_off" -> callServiceSync(domainOf(entityId), "turn_off", entityId, null);
            case "toggle"   -> callServiceSync(domainOf(entityId), "toggle", entityId, null);
            case "set_level" -> handleSetLevel(entityId, body);
            case "lock"      -> callServiceSync("lock", "lock", entityId, null);
            case "unlock"    -> callServiceSync("lock", "unlock", entityId, null);
            case "pincode"   -> handlePincode(entityId, subId, method, body);
            case "set_temperature" -> handleSetTemperature(entityId, body);
            case "set_hvac_mode"   -> handleSetHvacMode(entityId, body);
            case "open"          -> callServiceSync("cover", "open_cover", entityId, null);
            case "close"         -> callServiceSync("cover", "close_cover", entityId, null);
            case "stop"          -> callServiceSync("cover", "stop_cover", entityId, null);
            case "set_position"  -> handleSetPosition(entityId, body);
            case "set_speed"     -> handleSetFanSpeed(entityId, body);
            case "oscillate"     -> handleOscillate(entityId, body);
            case "set_direction" -> handleSetDirection(entityId, body);
            default -> Map.of("error", "unknown action: " + action);
        };
    }

    /** Same disambiguation buildActions() used, but action -> entityId instead of just names. */
    private Map<String, String> resolveActionEntities(List<Device> members) {
        List<ActionSource> sources = new ArrayList<>();
        for (Device dev : members) {
            String entityId = dev.getNode();
            HAState state = haInterface.getState(entityId);
            JsonNode attrs = state != null ? state.attributes() : null;
            for (String action : actionsFor(domainOf(entityId), attrs)) {
                sources.add(new ActionSource(action, entityId));
            }
        }
        Map<String, ActionSource> disambiguated =
                disambiguate(sources, ActionSource::entityId, ActionSource::action);
        Map<String, String> result = new LinkedHashMap<>();
        disambiguated.forEach((action, src) -> result.put(action, src.entityId()));
        return result;
    }

    /**
     * "turn_on_2" -> "turn_on" so the switch below can dispatch on the underlying HA semantics —
     * safe unconditionally because no action name in actionsFor() legitimately ends in "_<digits>".
     */
    private static String stripCollisionSuffix(String action) {
        return action.replaceFirst("_\\d+$", "");
    }

    private Map<String, Object> handleSetLevel(String entityId, Map<String, Object> body) {
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

    private Map<String, Object> handleSetTemperature(String entityId, Map<String, Object> body) {
        if (body == null) return Map.of("error", "body required");
        Map<String, Object> data = new LinkedHashMap<>();
        if (body.get("heat") != null) data.put("temperature", body.get("heat"));
        else if (body.get("cool") != null) data.put("temperature", body.get("cool"));
        else if (body.get("temperature") != null) data.put("temperature", body.get("temperature"));
        if (data.isEmpty()) return Map.of("error", "heat, cool, or temperature is required");
        return callServiceSync("climate", "set_temperature", entityId, data);
    }

    private Map<String, Object> handleSetHvacMode(String entityId, Map<String, Object> body) {
        Object mode = body != null ? body.get("mode") : null;
        if (mode == null) return Map.of("error", "mode is required");
        return callServiceSync("climate", "set_hvac_mode", entityId, Map.of("hvac_mode", mode));
    }

    private Map<String, Object> handleSetPosition(String entityId, Map<String, Object> body) {
        Object position = body != null ? body.get("position") : null;
        if (position == null) return Map.of("error", "position is required");
        return callServiceSync("cover", "set_cover_position", entityId, Map.of("position", position));
    }

    private Map<String, Object> handleSetFanSpeed(String entityId, Map<String, Object> body) {
        Object pct = body != null ? body.get("percentage") : null;
        if (pct == null) return Map.of("error", "percentage is required");
        return callServiceSync("fan", "set_percentage", entityId, Map.of("percentage", pct));
    }

    private Map<String, Object> handleOscillate(String entityId, Map<String, Object> body) {
        Object value = body != null ? body.get("value") : null;
        boolean oscillating = "on".equals(value) || Boolean.TRUE.equals(value);
        return callServiceSync("fan", "oscillate", entityId, Map.of("oscillating", oscillating));
    }

    private Map<String, Object> handleSetDirection(String entityId, Map<String, Object> body) {
        Object direction = body != null ? body.get("direction") : null;
        if (direction == null) return Map.of("error", "direction is required");
        return callServiceSync("fan", "set_direction", entityId, Map.of("direction", direction));
    }

    private Map<String, Object> handlePincode(String entityId, String subId, String method, Map<String, Object> body) {
        if (subId == null) return Map.of("error", "pincode slot is required");
        int slot;
        try {
            slot = Integer.parseInt(subId);
        } catch (NumberFormatException e) {
            return Map.of("error", "invalid pincode slot: " + subId);
        }
        return switch (method) {
            case "GET"    -> lockCodeProvider.getUserCode(entityId, slot);
            case "POST"   -> {
                Object code = body != null ? body.get("code") : null;
                if (code == null) yield Map.of("error", "code is required");
                yield lockCodeProvider.setUserCode(entityId, slot, String.valueOf(code));
            }
            case "DELETE" -> lockCodeProvider.deleteUserCode(entityId, slot);
            default -> Map.of("error", "unsupported method for pincode: " + method);
        };
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

    // ── Network-level inclusion / exclusion ──────────────────────────────────
    //
    // Routed per the `protocol` request field, which now selects which HA integration's
    // pairing flow to drive instead of talking to a radio directly:
    //   zwave  -> Z-Wave JS: NOT a call_service action -- a dedicated WS command namespace
    //             (zwave_js/add_node, zwave_js/remove_node, .../stop_inclusion,
    //             .../stop_exclusion) that requires the integration's config entry_id.
    //             Verified against home-assistant/core's zwave_js/api.py (2026-08).
    //   zigbee -> ZHA: a normal service, zha.permit (duration, default 60s). ZHA has no
    //             service to close the join window early, and zha.remove requires a
    //             specific device's ieee upfront (no network-wide "exclusion mode" the
    //             way Z-Wave/legacy Zigbee had) -- flagged below rather than guessed at.
    //   matter -> Home Assistant's Matter integration commissions devices through the
    //             interactive config-entry flow API, not a single RPC -- no headless
    //             equivalent was found. Falls back to a "commission via the HA UI" response
    //             per the migration plan's documented fallback.

    private static final String ZWAVE_JS_DOMAIN = "zwave_js";
    private static final String ZHA_DOMAIN      = "zha";

    public Map<String, Object> zwaveInclusion(String command, boolean blocking) {
        String entryId = resolveConfigEntryId(ZWAVE_JS_DOMAIN);
        if (entryId == null) return Map.of("error", "no zwave_js integration configured in Home Assistant");
        return switch (command) {
            // "start_s2" is not yet distinguished from "start" -- selecting an explicit S2
            // inclusion_strategy needs its wire-format enum value verified against the
            // target HA version before being sent; degrading to HA's default is safer than
            // guessing wrong and silently failing.
            case "start", "start_s2" -> sendZwaveJsCommand("zwave_js/add_node", Map.of("entry_id", entryId));
            case "stop"              -> sendZwaveJsCommand("zwave_js/stop_inclusion", Map.of("entry_id", entryId));
            default -> Map.of("error", "unknown zwave inclusion command: " + command);
        };
    }

    public Map<String, Object> zwaveExclusion(String command, boolean blocking) {
        String entryId = resolveConfigEntryId(ZWAVE_JS_DOMAIN);
        if (entryId == null) return Map.of("error", "no zwave_js integration configured in Home Assistant");
        return switch (command) {
            case "start" -> sendZwaveJsCommand("zwave_js/remove_node", Map.of("entry_id", entryId));
            case "stop"  -> sendZwaveJsCommand("zwave_js/stop_exclusion", Map.of("entry_id", entryId));
            default -> Map.of("error", "unknown zwave exclusion command: " + command);
        };
    }

    public Map<String, Object> zigbeeInclusion(String command) {
        if ("stop".equals(command)) {
            return Map.of("status", "not_supported",
                    "message", "ZHA has no service to close the join window early — it closes automatically after the permit duration");
        }
        return callServiceSync(ZHA_DOMAIN, "permit", null, Map.of("duration", 60));
    }

    public Map<String, Object> zigbeeExclusion() {
        return Map.of("status", "not_supported",
                "message", "ZHA requires a specific device's ieee address to remove it — use DELETE /:dev instead of network-level exclusion");
    }

    public Map<String, Object> matterInclusion() {
        return Map.of("status", "manual",
                "message", "Automatic Matter commissioning via this API is not yet supported — commission the device from "
                        + "the Home Assistant UI (Settings > Devices & services > Matter) or the Matter Server add-on's web UI; "
                        + "it will appear here automatically once commissioned");
    }

    private String resolveConfigEntryId(String domain) {
        try {
            JsonNode result = haInterface.sendCommandWait("config_entries/get", Map.of("domain", domain))
                    .orTimeout(10, TimeUnit.SECONDS).join();
            if (result != null && result.isArray() && !result.isEmpty()) {
                return result.get(0).path("entry_id").asText(null);
            }
        } catch (Exception e) {
            log.warn("Home Assistant: failed to resolve config entry for domain '{}': {}", domain, e.getMessage());
        }
        return null;
    }

    private Map<String, Object> sendZwaveJsCommand(String type, Map<String, Object> fields) {
        try {
            haInterface.sendCommandWait(type, fields).orTimeout(15, TimeUnit.SECONDS).join();
            return Map.of("status", "ok");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("Home Assistant: command '{}' failed: {}", type, cause.getMessage());
            return Map.of("error", cause.getMessage() != null ? cause.getMessage() : "command failed");
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

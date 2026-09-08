package uy.plomo.gateway.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uy.plomo.gateway.device.Device;
import uy.plomo.gateway.device.DeviceService;
import uy.plomo.gateway.platform.PlatformService;
import uy.plomo.gateway.sequence.Sequence;
import uy.plomo.gateway.sequence.SequenceService;
import uy.plomo.gateway.homeassistant.HomeAssistantController;
import uy.plomo.gateway.audio.AudioCommandService;
import uy.plomo.gateway.audio.AudioResponse;
import uy.plomo.gateway.ota.OtaService;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Central service layer for all gateway API operations.
 *
 * Used by:
 *   - REST controllers (thin HTTP wrappers)
 *   - MqttDispatcher (MQTT path routing → this service)
 *
 * Mirrors legacy Clojure routing logic.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GatewayApiService {

    private static final String FW_VERSION = "0.1";

    private final HomeAssistantController haController;
    private final OtaService       otaService;
    private final DeviceService    deviceService;
    private final SequenceService  sequenceService;
    private final PlatformService  platformService;

    private final ObjectMapper objectMapper;

    // Opcional — solo presente si audio.mqtt.enabled=true
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AudioCommandService audioCommandService;

    // ── Summary ───────────────────────────────────────────────────────────────

    /**
     * HAv1: one entry per physical HA device (all its entities grouped together — see
     * HomeAssistantController.buildGroupSummary()), not one entry per HA entity as before.
     * Entities with no HA device_id (helpers, some templates) form a group-of-one keyed by their
     * own row id. Non-"ha" rows (pre-migration leftovers, if any) pass through unchanged.
     */
    public Map<String, Object> getSummary() {
        Map<String, List<Device>> groups = new LinkedHashMap<>();
        deviceService.listAll().values().forEach(dev -> {
            String groupId = "ha".equals(dev.getProtocol())
                    ? groupIdOf(dev)
                    : dev.getId();
            groups.computeIfAbsent(groupId, k -> new ArrayList<>()).add(dev);
        });

        Map<String, Object> devices = new LinkedHashMap<>();
        groups.forEach((groupId, members) -> {
            Map<String, Object> parsed = "ha".equals(members.get(0).getProtocol())
                    ? haController.buildGroupSummary(groupId, members)
                    : Map.of("id", groupId);
            devices.put(groupId, parsed);
        });

        String tz  = platformService.getTimezone();
        String now;
        try {
            now = ZonedDateTime.now(ZoneId.of(tz))
                               .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (Exception e) {
            now = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("gw_id",      platformService.getSerialNumber());
        r.put("fw_version", FW_VERSION);
        r.put("version",    "HAv1");
        r.put("time",       now);
        r.put("timezone",   tz);
        r.put("devices",    devices);
        String pubkey = platformService.getPublicKey();
        if (pubkey != null) r.put("pubkey", pubkey);
        return r;
    }

    /** HA device_id if the entity belongs to one, else its own row id (group-of-one). */
    private static String groupIdOf(Device dev) {
        Object haDeviceId = dev.getAttribute("_meta", "ha_device_id");
        return haDeviceId != null ? haDeviceId.toString() : dev.getId();
    }

    // ── Inclusion / Exclusion ─────────────────────────────────────────────────

    // NOTE: as of the Home Assistant migration, these two dispatch to HomeAssistantController
    // rather than the direct-radio zwaveController/zigbeeController — inclusion/exclusion have
    // no meaningful way to run against "the real radio" and "HA" side by side for the same
    // protocol value, unlike getSummary/getDevice/handleDeviceCommand which stay additive via
    // the separate "ha" Device.protocol case. See the migration plan (M4) for the verified
    // per-integration API shapes this routes to.
    public Map<String, Object> inclusion(String protocol, String command, boolean blocking,
                                         Map<String, Object> body) {
        if (protocol == null || command == null)
            return Map.of("error", "protocol and command are required");
        return switch (protocol) {
            case "zwave"  -> haController.zwaveInclusion(command, blocking);
            case "zigbee" -> haController.zigbeeInclusion(command);
            case "matter" -> haController.matterInclusion();
            default       -> Map.of("error", "unknown protocol: " + protocol);
        };
    }

    public Map<String, Object> exclusion(String protocol, String command, boolean blocking) {
        if (protocol == null || command == null)
            return Map.of("error", "protocol and command are required");
        return switch (protocol) {
            case "zwave"  -> haController.zwaveExclusion(command, blocking);
            case "zigbee" -> haController.zigbeeExclusion();
            case "matter" -> Map.of("error", "exclusion not supported for matter — remove the device via DELETE /:dev or the Home Assistant UI");
            default       -> Map.of("error", "exclusion not supported for: " + protocol);
        };
    }

    // ── Platform ──────────────────────────────────────────────────────────────

    public Map<String, Object> setTimezone(String timezone) {
        if (timezone == null || timezone.isBlank())
            return Map.of("error", "timezone is required");
        boolean ok = platformService.setTimezone(timezone);
        return Map.of("status", ok ? "ok" : "error");
    }

    // ── Device CRUD ───────────────────────────────────────────────────────────

    public Map<String, Object> getDevice(String devId) {
        List<Device> members = deviceService.findGroupMembers(devId);
        if (members.isEmpty()) return Map.of("error", "device not found: " + devId);
        return "ha".equals(members.get(0).getProtocol())
                ? haController.buildGroupSummary(devId, members)
                : Map.of("id", devId);
    }

    /**
     * Deletes every local row in the device group — Home Assistant owns entity lifecycle, so
     * this does not reach into HA/the underlying integration to un-pair the device. Use the Home
     * Assistant UI (or the device's own reset procedure) to actually remove it from the mesh.
     */
    public Map<String, Object> deleteDevice(String devId) {
        List<Device> members = deviceService.findGroupMembers(devId);
        deviceService.deleteByIds(members.stream().map(Device::getId).toList());
        return Map.of("status", "deleted");
    }

    // ── Device commands ───────────────────────────────────────────────────────

    /**
     * Route a device command after resolving the device group from DB.
     *
     * @param devId   group id (HA device_id, or a group-of-one's own row id) from GET /summary
     * @param cmd     action name from that group's "actions" list, e.g. "lock", "turn_on" — plus
     *                the cross-protocol "fwd_event"/"name" below, which apply to the whole group
     * @param subId   optional sub-ID (e.g. pincode slot from path)
     * @param method  HTTP method string: GET | POST | DELETE
     * @param body    parsed request body
     */
    public Map<String, Object> handleDeviceCommand(
            String devId, String cmd, String subId, String method, Map<String, Object> body) {

        List<Device> members = deviceService.findGroupMembers(devId);
        if (members.isEmpty()) return Map.of("error", "device not found: " + devId);
        // fwd_event/name apply to the group as a whole — stored against whichever entity
        // buildGroupSummary() would also pick to represent it (haController.resolvePrimary uses
        // the same rule), so the two stay consistent.
        Device primary = "ha".equals(members.get(0).getProtocol())
                ? haController.resolvePrimary(members) : members.get(0);

        // Cross-protocol commands
        if ("fwd_event".equals(cmd)) {
            List<String> events = primary.getFwdEvents() == null
                    ? new ArrayList<>() : new ArrayList<>(primary.getFwdEvents());
            switch (method) {
                case "GET" -> { return Map.of("fwdEvents", events); }
                case "POST" -> {
                    String ev = str(body, "ev");
                    if (ev != null && !events.contains(ev)) events.add(ev);
                }
                case "PUT" -> {
                    Object raw = body.get("events");
                    if (raw instanceof List<?> list) {
                        events = list.stream()
                                .filter(e -> e instanceof String)
                                .map(e -> (String) e)
                                .distinct()
                                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
                    }
                }
                case "DELETE" -> {
                    String ev = str(body, "ev");
                    events.remove(ev);
                }
            }
            primary.setFwdEvents(events);
            deviceService.save(primary);
            return Map.of("status", "ok", "fwdEvents", events);
        }

        if ("name".equals(cmd) && "POST".equals(method)) {
            String name = str(body, "value");
            if (name != null) {
                primary.setName(name);
                deviceService.save(primary);
            }
            return Map.of("status", "ok");
        }

        // Protocol-specific dispatch
        String proto = primary.getProtocol();
        if (proto == null) return Map.of("error", "device has no protocol");
        return switch (proto) {
            case "ha" -> haController.handleDeviceCommand(devId, members, cmd, subId, method, body);
            default   -> Map.of("error", "unknown protocol: " + proto);
        };
    }

    // ── Sequences ─────────────────────────────────────────────────────────────

    public List<Map<String, Object>> listSequences() {
        return sequenceService.findAll().stream().map(this::seqToMap).toList();
    }

    public Map<String, Object> createSequence(Map<String, Object> body) {
        Sequence seq = new Sequence();
        seq.setName(str(body, "name"));
        try {
            Object steps = body.get("steps");
            seq.setSteps(steps != null ? objectMapper.writeValueAsString(steps) : "[]");
        } catch (Exception e) {
            seq.setSteps("[]");
        }
        return seqToMap(sequenceService.save(seq));
    }

    public Map<String, Object> getSequence(String id) {
        return sequenceService.findById(id)
                .map(this::seqToMap)
                .orElse(Map.of("error", "not found"));
    }

    public Map<String, Object> updateSequence(String id, Map<String, Object> body) {
        Optional<Sequence> opt = sequenceService.findById(id);
        if (opt.isEmpty()) return Map.of("error", "not found");
        Sequence seq = opt.get();
        if (body.containsKey("name"))  seq.setName(str(body, "name"));
        if (body.containsKey("steps")) {
            try { seq.setSteps(objectMapper.writeValueAsString(body.get("steps"))); }
            catch (Exception ignored) {}
        }
        return seqToMap(sequenceService.save(seq));
    }

    public Map<String, Object> deleteSequence(String id) {
        sequenceService.deleteById(id);
        return Map.of("status", "deleted");
    }

    /** Sequence execution is a stub — step runner not yet implemented. */
    public Map<String, Object> runSequence(String id) {
        log.info("run-sequence {} (execution engine TODO)", id);
        return Map.of("status", "started", "id", id);
    }

    // ── SSH tunnels ───────────────────────────────────────────────────────────

    /**
     * Handle POST /tunnel { cmd: "start"|"stop"|"list", ... }.
     * Mirrors legacy Clojure start-tunnel and stop-tunnel.
     */
    public Map<String, Object> handleTunnel(Map<String, Object> body) {
        String cmd = str(body, "cmd");
        if (cmd == null) cmd = str(body, "command");
        return switch (cmd != null ? cmd : "") {
            case "start" -> {
                String srcAddr = str(body, "src-addr");
                String dstAddr = str(body, "dst-addr");
                int srcPort = intVal(body, "src-port");
                int dstPort = intVal(body, "dst-port");
                if (srcAddr == null || dstAddr == null || srcPort == 0 || dstPort == 0)
                    yield Map.of("error", "src-addr, src-port, dst-addr, dst-port are required");
                yield platformService.createReverseTunnel(srcAddr, srcPort, dstAddr, dstPort);
            }
            case "stop" -> {
                String srcAddr = str(body, "src-addr");
                String dstAddr = str(body, "dst-addr");
                int srcPort = intVal(body, "src-port");
                int dstPort = intVal(body, "dst-port");
                if (srcPort != 0 || dstPort != 0)
                    yield platformService.stopRunningTunnel(srcAddr, srcPort, dstAddr, dstPort);
                yield platformService.stopRunningTunnels();
            }
            case "list" -> Map.of("tunnels", platformService.listRunningTunnels());
            default -> Map.of("error", "unknown tunnel cmd — use start|stop|list");
        };
    }

    // ── Debug / test endpoint ─────────────────────────────────────────────────

    /**
     * Hands-on debug endpoint, callable via HTTP or MQTT.
     *
     * Commands:
     *   ping — basic liveness check
     */
    public Map<String, Object> handleTest(Map<String, Object> body) {
        String cmd = str(body, "cmd");
        if (cmd == null) cmd = "ping";
        return switch (cmd) {
            case "ping" -> Map.of("status", "ok", "msg", "pong");
            default -> Map.of("error", "unknown test cmd: " + cmd + " — use: ping");
        };
    }

    // ── Schedule (stub) ───────────────────────────────────────────────────────

    public Map<String, Object> getSchedule() {
        return Map.of("schedules", List.of());
    }

    public Map<String, Object> createSchedule(Map<String, Object> body) {
        log.info("createSchedule (TODO): {}", body);
        return Map.of("status", "not implemented");
    }

    public Map<String, Object> getScheduleItem(String id) {
        return Map.of("error", "not found");
    }

    public Map<String, Object> deleteScheduleItem(String id) {
        return Map.of("status", "not implemented");
    }

    // ── MQTT dispatch ─────────────────────────────────────────────────────────

    private static final Pattern P3 = Pattern.compile("/([^/]+)/([^/]+)/([^/]+)");
    private static final Pattern P2 = Pattern.compile("/([^/]+)/([^/]+)");
    private static final Pattern P1 = Pattern.compile("/([^/]+)");

    /**
     * Route an MQTT command to the correct service method.
     * Called by {@link uy.plomo.gateway.mqtt.MqttDispatcher}.
     *
     * @param method   HTTP-style method (GET, POST, DELETE)
     * @param path     API path, e.g. "/summary" or "/{devId}/pincode/3"
     * @param bodyJson raw JSON body string (may be null or "{}")
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> dispatch(String method, String path, String bodyJson) {
        Map<String, Object> body = Map.of();
        if (bodyJson != null && !bodyJson.isBlank() && !bodyJson.equals("{}")) {
            try { body = objectMapper.readValue(bodyJson, Map.class); }
            catch (Exception e) { log.warn("dispatch: cannot parse body: {}", bodyJson); }
        }
        if (path != null && path.startsWith("/api/v1")) path = path.substring("/api/v1".length());
        if (path == null || path.isEmpty()) path = "/";
        return route(method, path, body);
    }

    private Map<String, Object> route(String method, String path, Map<String, Object> body) {
        // Fixed routes
        if (is("GET",    "/summary",   method, path)) return getSummary();
        if (is("POST",   "/include",   method, path)) return inclusion(str(body,"protocol"), str(body,"command"), bool(body,"blocking"), body);
        if (is("POST",   "/ota",       method, path)) return otaService.update(str(body,"url"), str(body,"checksum"));
        if (is("POST",   "/exclude",   method, path)) return exclusion(str(body,"protocol"), str(body,"command"), bool(body,"blocking"));
        if (is("POST",   "/timezone",  method, path)) return setTimezone(str(body,"timezone"));
        if (is("POST",   "/tunnel",    method, path)) return handleTunnel(body);
        if (is("POST",   "/test",      method, path)) return handleTest(body);
        if (is("GET",    "/test",      method, path)) return handleTest(Map.of("cmd", "ping"));
        if (is("GET",    "/tunnel",    method, path)) return Map.of("tunnels", platformService.listRunningTunnels());
        if (is("GET",    "/schedule",  method, path)) return getSchedule();
        if (is("POST",   "/schedule",  method, path)) return createSchedule(body);
        if (is("GET",    "/sequences", method, path)) return Map.of("sequences", listSequences());
        if (is("POST",   "/sequences", method, path)) return createSequence(body);

        // /sequences/:id/run
        if ("POST".equals(method) && path.matches("/sequences/[^/]+/run")) {
            String id = path.substring("/sequences/".length(), path.length() - "/run".length());
            return runSequence(id);
        }

        // /sequences/:id
        if (path.matches("/sequences/[^/]+")) {
            String id = path.substring("/sequences/".length());
            return switch (method) {
                case "GET"    -> getSequence(id);
                case "PUT", "POST" -> updateSequence(id, body);
                case "DELETE" -> deleteSequence(id);
                default       -> Map.of("error", "method not allowed");
            };
        }

        // /schedule/:id
        if (path.matches("/schedule/[^/]+")) {
            String id = path.substring("/schedule/".length());
            return switch (method) {
                case "GET"    -> getScheduleItem(id);
                case "DELETE" -> deleteScheduleItem(id);
                default       -> Map.of("error", "method not allowed");
            };
        }

        // /audio/:cmd — audio inference service commands
        if (path.startsWith("/audio/")) {
            String cmd = path.substring("/audio/".length());
            return handleAudioCommand(cmd, method, body);
        }

        // /matter/:cmd — Matter network management commands (now routed through Home Assistant)
        if (path.startsWith("/matter/")) {
            String cmd = path.substring("/matter/".length());
            return handleMatterNetwork(cmd, method, body);
        }

        // /cameras — camera network management
        if (is("GET", "/cameras", method, path)) return handleCameraNetwork("list", method, body);
        if (path.startsWith("/cameras/")) {
            String sub = path.substring("/cameras/".length());
            return handleCameraNetwork(sub, method, body);
        }

        // /:dev/:cmd/:id
        Matcher m3 = P3.matcher(path);
        if (m3.matches()) return handleDeviceCommand(m3.group(1), m3.group(2), m3.group(3), method, body);

        // /:dev/:cmd
        Matcher m2 = P2.matcher(path);
        if (m2.matches()) return handleDeviceCommand(m2.group(1), m2.group(2), null, method, body);

        // /:dev (GET or DELETE)
        Matcher m1 = P1.matcher(path);
        if (m1.matches()) {
            String devId = m1.group(1);
            return switch (method) {
                case "GET"    -> getDevice(devId);
                case "DELETE" -> deleteDevice(devId);
                default       -> Map.of("error", "method not allowed");
            };
        }

        return Map.of("error", "not found: " + method + " " + path);
    }

    // ── Audio commands ────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleAudioCommand(String cmd, String method, Map<String, Object> body) {
        if (audioCommandService == null)
            return Map.of("error", "audio service not enabled (audio.mqtt.enabled=false)");
        return switch (cmd) {
            case "status" -> audioResponseToMap(audioCommandService.sendStatus());
            case "reload" -> audioResponseToMap(audioCommandService.sendReloadConfig());
            case "learn"  -> {
                int duration = intVal(body, "duration");
                yield audioResponseToMap(audioCommandService.sendLearn(
                        str(body, "camera_id"),
                        str(body, "event_name"),
                        duration > 0 ? duration : 30));
            }
            case "learn-apply" -> {
                Map<String, Object> entry = (Map<String, Object>) body.get("entry");
                yield audioResponseToMap(audioCommandService.sendLearnApply(
                        str(body, "learn_id"),
                        str(body, "event_name"),
                        entry));
            }
            default -> Map.of("error", "unknown audio command: " + cmd
                    + " — use: status | reload | learn | learn-apply");
        };
    }

    private Map<String, Object> audioResponseToMap(AudioResponse response) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", response.getAction());
        result.put("status", response.getStatus());
        if (response.getData() != null) result.putAll(response.getData());
        return result;
    }

    // ── Matter network commands ───────────────────────────────────────────────

    /**
     * POST /matter/commission { code: "MT:Y..." }  — no headless equivalent exists in Home
     *   Assistant's Matter integration (it commissions through an interactive config-entry
     *   flow, not a single RPC — see HomeAssistantController.matterInclusion()), so this
     *   returns a "commission via the HA UI" response rather than actually commissioning.
     * POST /matter/remove, GET /matter/nodes — no HA equivalent found either; Matter devices
     *   are now just regular Home Assistant devices, visible via GET /summary like anything
     *   else, and removable via DELETE /:dev (which deletes only the gateway's local record).
     */
    public Map<String, Object> handleMatterNetwork(
            String cmd, String method, Map<String, Object> body) {
        return switch (cmd) {
            case "commission" -> haController.matterInclusion();
            case "remove", "nodes" -> Map.of("error",
                    "not supported via this API anymore — Matter devices are managed as regular "
                            + "Home Assistant devices; see GET /summary, or DELETE /:dev to remove the "
                            + "gateway's local record");
            default -> Map.of("error", "unknown matter command: " + cmd
                    + " — use: commission | remove | nodes");
        };
    }

    // ── Camera network commands ───────────────────────────────────────────────

    /**
     * Camera network management — routed from both MQTT and REST (see also
     * api/CameraRestController for the REST-side snapshot proxy). Camera setup happens in
     * the Home Assistant UI now; this only lists and removes.
     *   GET    /cameras      → list
     *   DELETE /cameras/{id} → remove device row
     */
    public Map<String, Object> handleCameraNetwork(
            String cmd, String method, Map<String, Object> body) {
        return switch (cmd) {
            case "list" -> {
                List<Device> cameras = deviceService.findByProtocol("ha").stream()
                        .filter(d -> d.getNode() != null && d.getNode().startsWith("camera."))
                        .toList();
                yield Map.of("cameras", cameras.stream()
                        .map(d -> haController.parseCameraDevice(d.getId(), d))
                        .toList());
            }
            default -> {
                // DELETE /cameras/{id}
                if ("DELETE".equals(method)) {
                    deleteDevice(cmd); // cmd holds the device id here
                    yield Map.of("status", "deleted");
                }
                yield Map.of("error", "unknown camera command: " + cmd + " — use: list");
            }
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean is(String m, String p, String method, String path) {
        return m.equals(method) && p.equals(path);
    }

    public String str(Map<String, Object> m, String key) {
        if (m == null) return null;
        Object v = m.get(key);
        return v != null ? v.toString() : null;
    }

    public boolean bool(Map<String, Object> m, String key) {
        if (m == null) return false;
        Object v = m.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s)  return Boolean.parseBoolean(s);
        return false;
    }

    public int intVal(Map<String, Object> m, String key) {
        if (m == null) return 0;
        Object v = m.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) { try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {} }
        return 0;
    }

    private Map<String, Object> seqToMap(Sequence seq) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",    seq.getId());
        m.put("name",  seq.getName());
        m.put("steps", seq.getSteps());
        return m;
    }
}

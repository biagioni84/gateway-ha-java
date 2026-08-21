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
import uy.plomo.gateway.camera.CameraController;
import uy.plomo.gateway.homeassistant.HomeAssistantController;
import uy.plomo.gateway.matter.MatterController;
import uy.plomo.gateway.matter.MatterInterface;
import uy.plomo.gateway.matter.MatterNode;
import uy.plomo.gateway.audio.AudioCommandService;
import uy.plomo.gateway.audio.AudioResponse;
import uy.plomo.gateway.ota.OtaService;
import uy.plomo.gateway.zigbee.ZigbeeController;
import uy.plomo.gateway.zwave.ZWaveController;

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

    private final ZWaveController  zwaveController;
    private final ZigbeeController zigbeeController;
    private final MatterController matterController;
    private final MatterInterface  matterInterface;
    private final CameraController cameraController;
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

    public Map<String, Object> getSummary() {
        Map<String, Object> devices = new LinkedHashMap<>();
        deviceService.listAll().forEach((id, dev) -> {
            Map<String, Object> parsed = switch (dev.getProtocol() != null ? dev.getProtocol() : "") {
                case "zwave"  -> zwaveController.parseDevice(id, dev);
                case "zigbee" -> zigbeeController.parseDevice(id, dev);
                case "matter" -> matterController.parseDevice(id, dev);
                case "camera" -> cameraController.parseDevice(id, dev);
                case "ha"     -> haController.parseDevice(id, dev);
                default       -> Map.of("id", id);
            };
            devices.put(id, parsed);
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
        r.put("time",       now);
        r.put("timezone",   tz);
        r.put("devices",    devices);
        String pubkey = platformService.getPublicKey();
        if (pubkey != null) r.put("pubkey", pubkey);
        return r;
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
        Optional<Device> opt = deviceService.findById(devId);
        if (opt.isEmpty()) return Map.of("error", "device not found: " + devId);
        Device dev = opt.get();
        return switch (dev.getProtocol() != null ? dev.getProtocol() : "") {
            case "zwave"  -> zwaveController.parseDevice(devId, dev);
            case "zigbee" -> zigbeeController.parseDevice(devId, dev);
            case "matter" -> matterController.parseDevice(devId, dev);
            case "camera" -> cameraController.parseDevice(devId, dev);
            case "ha"     -> haController.parseDevice(devId, dev);
            default       -> Map.of("id", devId);
        };
    }

    public Map<String, Object> deleteDevice(String devId) {
        Optional<Device> opt = deviceService.findById(devId);
        if (opt.isPresent() && "matter".equals(opt.get().getProtocol())) {
            String nodeStr = opt.get().getNode();
            if (nodeStr != null) {
                try {
                    long nodeId = nodeStr.startsWith("0x") || nodeStr.startsWith("0X")
                            ? Long.parseLong(nodeStr.substring(2), 16)
                            : Long.parseLong(nodeStr);
                    matterController.removeNode(nodeId)
                            .orTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                            .exceptionally(ex -> {
                                log.warn("Matter removeNode {} failed: {}", nodeId, ex.getMessage());
                                return null;
                            });
                } catch (NumberFormatException e) {
                    log.warn("deleteDevice: invalid matter node id '{}' for device {}", nodeStr, devId);
                }
            }
        }
        deviceService.deleteById(devId);
        return Map.of("status", "deleted");
    }

    // ── Device commands ───────────────────────────────────────────────────────

    /**
     * Route a device command after resolving the device from DB.
     *
     * @param devId   UUID of the device
     * @param cmd     command name, e.g. "lock", "pincode", "switch", "thermostat"
     * @param subId   optional sub-ID (e.g. pincode slot from path)
     * @param method  HTTP method string: GET | POST | DELETE
     * @param body    parsed request body
     */
    public Map<String, Object> handleDeviceCommand(
            String devId, String cmd, String subId, String method, Map<String, Object> body) {

        Optional<Device> opt = deviceService.findById(devId);
        if (opt.isEmpty()) return Map.of("error", "device not found: " + devId);
        Device dev = opt.get();

        // Cross-protocol commands
        if ("fwd_event".equals(cmd)) {
            List<String> events = dev.getFwdEvents() == null
                    ? new ArrayList<>() : new ArrayList<>(dev.getFwdEvents());
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
            dev.setFwdEvents(events);
            deviceService.save(dev);
            return Map.of("status", "ok", "fwdEvents", events);
        }

        if ("name".equals(cmd) && "POST".equals(method)) {
            String name = str(body, "value");
            if (name != null) {
                dev.setName(name);
                deviceService.save(dev);
            }
            return Map.of("status", "ok");
        }

        // Protocol-specific dispatch
        String proto = dev.getProtocol();
        if (proto == null) return Map.of("error", "device has no protocol");
        return switch (proto) {
            case "zwave"  -> zwaveController.handleDeviceCommand(dev, cmd, subId, method, body);
            case "zigbee" -> zigbeeController.handleDeviceCommand(dev, cmd, subId, method, body);
            case "matter" -> handleMatterDeviceCommand(dev, cmd, method, body);
            case "camera" -> cameraController.handleDeviceCommand(dev, cmd, method, body);
            case "ha"     -> haController.handleDeviceCommand(dev, cmd, subId, method, body);
            default       -> Map.of("error", "unknown protocol: " + proto);
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
     *   ping                       — basic liveness check
     *   zwave_node_list            — trigger NODE_LIST_GET to zipgateway
     *   zwave_setup_node  node=N   — manually call setup(N) for a known node
     */
    public Map<String, Object> handleTest(Map<String, Object> body) {
        String cmd = str(body, "cmd");
        if (cmd == null) cmd = "ping";
        return switch (cmd) {
            case "ping" -> Map.of("status", "ok", "msg", "pong");

            case "zwave_node_list" -> {
                try {
                    yield zwaveController.requestNodeList().get(15, java.util.concurrent.TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    yield Map.of("error", "timeout waiting for NODE_LIST_REPORT");
                } catch (Exception e) {
                    yield Map.of("error", e.getMessage());
                }
            }

            case "zwave_setup_node" -> {
                String nodeStr = str(body, "node");
                if (nodeStr == null) yield Map.of("error", "node required");
                try {
                    int nodeId = nodeStr.startsWith("0x") || nodeStr.startsWith("0X")
                            ? Integer.parseInt(nodeStr.substring(2), 16)
                            : Integer.parseInt(nodeStr);
                    zwaveController.setup(nodeId);
                    yield Map.of("status", "ok", "node", nodeId);
                } catch (NumberFormatException e) {
                    yield Map.of("error", "invalid node: " + nodeStr);
                }
            }

            default -> Map.of("error", "unknown test cmd: " + cmd
                    + " — use: ping | zwave_node_list | zwave_setup_node");
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

        // /zwave/:cmd  — Z-Wave network management commands
        if (path.startsWith("/zwave/")) {
            String cmd = path.substring("/zwave/".length());
            return handleZwaveNetwork(cmd, method, body);
        }

        // /zigbee/:cmd — Zigbee network management commands (future)
        if (path.startsWith("/zigbee/")) {
            return Map.of("status", "not implemented");
        }

        // /matter/:cmd — Matter network management commands
        if (path.startsWith("/matter/")) {
            String cmd = path.substring("/matter/".length());
            return handleMatterNetwork(cmd, method, body);
        }

        // /cameras — camera network management
        if (is("GET",  "/cameras", method, path)) return handleCameraNetwork("list",     method, body);
        if (is("POST", "/cameras", method, path)) return handleCameraNetwork("add",      method, body);
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

    // ── Z-Wave network commands ───────────────────────────────────────────────

    /**
     * Mirrors handle-network in zwave/controller.clj:
     *   GET  region
     *   POST region  { region: "0x00"|"0x01" }
     *   POST update_network
     */
    public Map<String, Object> handleZwaveNetwork(
            String cmd, String method, Map<String, Object> body) {
        // /zwave/interview/{nodeId}
        if (cmd.startsWith("interview/")) {
            String nodeStr = cmd.substring("interview/".length());
            try {
                int nodeId = nodeStr.startsWith("0x") || nodeStr.startsWith("0X")
                        ? Integer.parseUnsignedInt(nodeStr.substring(2), 16)
                        : Integer.parseInt(nodeStr);
                return zwaveController.interview(nodeId);
            } catch (NumberFormatException e) {
                return Map.of("error", "invalid nodeId: " + nodeStr);
            }
        }

        // /zwave/association/{nodeId}
        if (cmd.startsWith("association/")) {
            String nodeStr = cmd.substring("association/".length());
            try {
                int nodeId = nodeStr.startsWith("0x") || nodeStr.startsWith("0X")
                        ? Integer.parseUnsignedInt(nodeStr.substring(2), 16)
                        : Integer.parseInt(nodeStr);
                int group  = intVal(body, "group");
                if (group <= 0) return Map.of("error", "group is required and must be > 0");
                int target = intVal(body, "target");
                if (target <= 0) target = 1;
                String nodeHex = String.format("0x%02X", nodeId);
                return zwaveController.associationSet(nodeHex, group, target);
            } catch (NumberFormatException e) {
                return Map.of("error", "invalid nodeId: " + nodeStr);
            }
        }
        return switch (cmd) {
            case "region" -> {
                if ("POST".equals(method)) {
                    String region = str(body, "region");
                    // Delegate to PlatformService (region stored in zipgateway.cfg)
                    yield Map.of("status", "not implemented", "note",
                            "set-zwave-region requires zipgateway.cfg access — Phase 7");
                }
                yield Map.of("status", "not implemented", "note",
                        "get-zwave-region requires zipgateway.cfg access — Phase 7");
            }
            case "update_network" -> {
                if ("POST".equals(method)) {
                    zwaveController.requestNodeList();
                    yield Map.of("status", "ok", "note", "node list refresh requested");
                }
                yield Map.of("error", "use POST for update_network");
            }
            default -> Map.of("error", "unknown zwave network command: " + cmd);
        };
    }

    // ── Matter network commands ───────────────────────────────────────────────

    /**
     * Commission a Matter device and return a device-summary response.
     *
     * 1. Calls python-matter-server commission_with_code (blocks up to 65 s).
     * 2. Extracts node_id from the response.
     * 3. Waits up to 3 s for node_added → setup() to write the DB entry.
     * 4. Returns { status, device: <parseDevice summary> }.
     *
     * Used by both POST /include { protocol:"matter", code } and
     * POST /matter/commission { code }.
     */
    private Map<String, Object> commissionMatterDevice(String code) {
        if (code == null || code.isBlank()) return Map.of("error", "code is required");
        try {
            com.fasterxml.jackson.databind.JsonNode result =
                    matterController.commissionWithCode(code)
                            .get(65, java.util.concurrent.TimeUnit.SECONDS);

            long nodeId = result != null ? result.path("node_id").asLong(-1) : -1;
            if (nodeId < 0) return Map.of("error", "commissioning succeeded but no node_id in response");

            // node_added fires asynchronously — wait for setup() to write the DB entry
            String nodeStr = String.valueOf(nodeId);
            Optional<Device> devOpt = Optional.empty();
            for (int i = 0; i < 10; i++) {
                devOpt = deviceService.findByNode(nodeStr);
                if (devOpt.isPresent()) break;
                Thread.sleep(300);
            }

            if (devOpt.isEmpty()) {
                return Map.of("status", "commissioned", "node_id", nodeId,
                        "note", "device entry not yet created — retry GET /summary");
            }
            Device dev = devOpt.get();
            return Map.of("status", "ok", "device", matterController.parseDevice(dev.getId(), dev));

        } catch (java.util.concurrent.TimeoutException e) {
            return Map.of("error", "commissioning timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of("error", "interrupted");
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    /**
     * POST /matter/commission  { code: "MT:Y..." }
     * POST /matter/remove      { node_id: 9 }
     * GET  /matter/nodes
     */
    public Map<String, Object> handleMatterNetwork(
            String cmd, String method, Map<String, Object> body) {
        return switch (cmd) {
            case "commission" -> commissionMatterDevice(str(body, "code"));
            case "remove" -> {
                Object nodeIdObj = body.get("node_id");
                if (nodeIdObj == null) yield Map.of("error", "node_id is required");
                try {
                    long nodeId = Long.parseLong(nodeIdObj.toString());
                    yield Map.of("result", matterController.removeNode(nodeId)
                            .get(10, java.util.concurrent.TimeUnit.SECONDS));
                } catch (java.util.concurrent.TimeoutException e) {
                    yield Map.of("error", "remove timed out");
                } catch (Exception e) {
                    yield Map.of("error", e.getMessage());
                }
            }
            case "nodes" -> {
                try {
                    yield Map.of("nodes", matterController.getNodes()
                            .get(10, java.util.concurrent.TimeUnit.SECONDS));
                } catch (Exception e) {
                    yield Map.of("error", e.getMessage());
                }
            }
            default -> Map.of("error", "unknown matter command: " + cmd
                    + " — use: commission | remove | nodes");
        };
    }

    // ── Camera network commands ───────────────────────────────────────────────

    /**
     * Camera network management — routed from both MQTT and REST.
     *   GET  /cameras        → list
     *   POST /cameras        → add { name, src } or { name, ip, username?, password? }
     *   POST /cameras/discover → ONVIF scan (no credentials)
     *   DELETE /cameras/{id} → remove device
     */
    public Map<String, Object> handleCameraNetwork(
            String cmd, String method, Map<String, Object> body) {
        return switch (cmd) {
            case "list"    -> {
                List<uy.plomo.gateway.device.Device> cameras =
                        deviceService.findByProtocol("camera");
                yield Map.of("cameras", cameras.stream()
                        .map(d -> cameraController.parseDevice(d.getId(), d))
                        .toList());
            }
            case "add"     -> {
                String ip   = str(body, "ip");
                String type = str(body, "type");
                if ("ONVIF".equalsIgnoreCase(type) || ip != null) {
                    yield cameraController.addOnvifCamera(
                            str(body, "name"), ip,
                            str(body, "username"), str(body, "password"),
                            str(body, "managementUrl"));
                }
                yield cameraController.addCamera(str(body, "name"), str(body, "src"));
            }
            case "discover" -> cameraController.discoverCameras();
            default -> {
                // DELETE /cameras/{id}
                if ("DELETE".equals(method)) {
                    deleteDevice(cmd); // cmd holds the device id here
                    yield Map.of("status", "deleted");
                }
                yield Map.of("error", "unknown camera command: " + cmd
                        + " — use: discover | add | list");
            }
        };
    }

    /**
     * Handle a device command for a Matter device.
     * Supported commands:
     *   on / off / toggle          — OnOff cluster (6), endpoint 1
     *   level  { value: 0-254 }    — LevelControl cluster (8)
     *   command { endpoint, cluster, command, args } — raw cluster command
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> handleMatterDeviceCommand(
            Device dev, String cmd, String method, Map<String, Object> body) {

        long nodeId;
        try {
            nodeId = Long.parseLong(dev.getNode());
        } catch (Exception e) {
            return Map.of("error", "invalid matter node: " + dev.getNode());
        }

        int endpoint = body != null && body.get("endpoint") instanceof Number n
                ? n.intValue() : 1;

        // GET switch — read from node cache, no future needed
        if ("switch".equals(cmd) && "GET".equals(method)) {
            MatterNode cached = matterInterface.getNode(nodeId);
            Object value = (cached != null && cached.raw() != null)
                    ? MatterController.inferStatus(cached.raw().path("attributes"), "switch")
                    : null;
            return Map.of("status", "ok", "value", value != null ? value : "unknown");
        }

        try {
            var future = switch (cmd) {
                case "on"     -> matterController.turnOn(nodeId, endpoint);
                case "off"    -> matterController.turnOff(nodeId, endpoint);
                case "toggle" -> matterController.toggle(nodeId, endpoint);
                case "switch" -> {
                    String val = str(body, "value");
                    yield "on".equals(val) || "true".equals(val)
                            ? matterController.turnOn(nodeId, endpoint)
                            : matterController.turnOff(nodeId, endpoint);
                }
                case "level"  -> {
                    int level = intVal(body, "value");
                    yield matterController.setLevel(nodeId, endpoint, level, 0);
                }
                case "command" -> {
                    int    clusterId   = intVal(body, "cluster");
                    String commandName = str(body, "command");
                    if (commandName == null) yield null;
                    Object argsObj = body != null ? body.get("args") : null;
                    Map<String, Object> args = argsObj instanceof Map<?, ?> m2
                            ? (Map<String, Object>) m2 : Map.of();
                    yield matterController.deviceCommand(nodeId, endpoint, clusterId, commandName, args);
                }
                default -> null;
            };
            if (future == null) return Map.of("error", "unknown matter command: " + cmd);
            future.get(10, java.util.concurrent.TimeUnit.SECONDS);
            return Map.of("status", "ok");
        } catch (java.util.concurrent.TimeoutException e) {
            return Map.of("error", "matter command timed out");
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
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

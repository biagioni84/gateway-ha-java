package uy.plomo.gateway.zwave;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import uy.plomo.gateway.descriptor.DeviceDescriptorService;
import uy.plomo.gateway.descriptor.ResolvedDescriptor;
import uy.plomo.gateway.descriptor.ZWaveDescriptor;
import uy.plomo.gateway.device.Device;
import uy.plomo.gateway.device.DeviceService;
import uy.plomo.gateway.platform.PlatformService;
import uy.plomo.zwave.ZWaveProtocol;

import java.time.*;
import java.time.zone.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Z-Wave business logic: inclusion/exclusion, device commands, setup.
 *
 * Mirrors the active code in zwave/controller.clj.
 *
 * Frame building uses ZWaveProtocol.createZipPacket (map-based options).
 * Parsing uses Map<String,Object> returned by ZWaveProtocol.parseFrame (via ZWaveInterface).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ZWaveController {

    // Zipgateway network controller address (management plane)
    private static final String ZIPGATEWAY_CTRL_IP = "fd00:aaaa::3";

    @Value("${zwave.zipgateway.host.prefix:fd00:bbbb::}")
    private String hostPrefix;

    private final ZWaveInterface          zwaveInterface;
    private final DeviceService           deviceService;
    private final ZWaveProtocol           zwaveProtocol;
    private final DeviceDescriptorService descriptorService;
    private final PlatformService         platformService;

    // ── Network management ────────────────────────────────────────────────────

    /**
     * Start/stop Z-Wave node inclusion.
     * cmd: "start" | "start_s2" | "stop"
     * Mirrors (inclusion cmd blocking?) in zwave/controller.clj.
     */
    public Map<String, Object> inclusion(String cmd, boolean blocking) {
        String mode = switch (cmd) {
            case "start"    -> "ADD_NODE_ANY";
            case "start_s2" -> "NODE_ADD_ANY_S2";
            case "stop"     -> "ADD_NODE_STOP";
            default         -> throw new IllegalArgumentException("Unknown inclusion cmd: " + cmd);
        };
        return sendNetworkMgmtFrame("COMMAND_NODE_ADD", Map.of("mode", mode,
                "txOptions", "TRANSMIT_OPTION_EXPLORE"), blocking);
    }

    /**
     * Start/stop Z-Wave node exclusion.
     * cmd: "start" | "stop"
     */
    public Map<String, Object> exclusion(String cmd, boolean blocking) {
        String mode = switch (cmd) {
            case "start" -> "REMOVE_NODE_ANY";
            case "stop"  -> "REMOVE_NODE_STOP";
            default      -> throw new IllegalArgumentException("Unknown exclusion cmd: " + cmd);
        };
        return sendNetworkMgmtFrame("COMMAND_NODE_REMOVE", Map.of("mode", mode), blocking);
    }

    /**
     * Request the current node list from zipgateway.
     * The response triggers NODE_LIST_REPORT via the unsolicited listener.
     */
    public CompletableFuture<Map<String, Object>> requestNodeList() {
        int seq = nextSeq();
        List<String> hexBytes = zwaveProtocol.createZipPacket(Map.of(
                "header", Map.of(
                        "p1", Map.of("ACK_REQUEST_BIT_MASK_V2", true),
                        "p2", Map.of("SECURE_ORIGIN_BIT_MASK_V2", true, "Z_WAVE_CMD_INCLUDED_BIT_MASK_V2", true),
                        "seq", seq),
                "class", "NETWORK_MANAGEMENT_PROXY",
                "cmd",   "COMMAND_NODE_LIST_GET"));
        log.debug("NODE_LIST_GET sent (seq={}) → {}", seq, String.join("", hexBytes));

        Predicate<Map<String, Object>> matcher = pkt -> {
            Map<String, Object> zwCmd = getZwaveCmd(pkt);
            return zwCmd != null
                    && "NETWORK_MANAGEMENT_PROXY".equals(zwCmd.get("class"))
                    && "COMMAND_NODE_LIST_REPORT".equals(zwCmd.get("command"));
        };

        return zwaveInterface.sendAndWait(hexListToBytes(hexBytes), ZIPGATEWAY_CTRL_IP, matcher)
                .orTimeout(15, TimeUnit.SECONDS);
    }

    // ── Switch / Dimmer ───────────────────────────────────────────────────────

    public Map<String, Object> switchBinary(String nodeHex, boolean on) {
        int seq = nextSeq();
        List<String> hexBytes = zwaveProtocol.createZipPacket(Map.of(
                "header", Map.of(
                        "p1", Map.of("ACK_REQUEST_BIT_MASK_V2", true),
                        "p2", Map.of("SECURE_ORIGIN_BIT_MASK_V2", true, "Z_WAVE_CMD_INCLUDED_BIT_MASK_V2", true),
                        "seq", seq),
                "class",       "SWITCH_BINARY",
                "cmd",         "SWITCH_BINARY_SET",
                "switchValue", on ? "ff" : "00"));
        Map<String, Object> resp = zwaveInterface.sendAndReceive(
                hexListToBytes(hexBytes), zwaveInterface.nodeIpFromHex(nodeHex), seq);
        return responseToMap(resp, on ? "on" : "off");
    }

    public Map<String, Object> switchMultilevel(String nodeHex, int level) {
        int seq = nextSeq();
        List<String> hexBytes = zwaveProtocol.createZipPacket(Map.of(
                "header", Map.of(
                        "p1", Map.of("ACK_REQUEST_BIT_MASK_V2", true),
                        "p2", Map.of("SECURE_ORIGIN_BIT_MASK_V2", true, "Z_WAVE_CMD_INCLUDED_BIT_MASK_V2", true),
                        "seq", seq),
                "class", "SWITCH_MULTILEVEL",
                "cmd",   "SWITCH_MULTILEVEL_SET",
                "value", String.format("%02x", level)));
        Map<String, Object> resp = zwaveInterface.sendAndReceive(
                hexListToBytes(hexBytes), zwaveInterface.nodeIpFromHex(nodeHex), seq);
        return responseToMap(resp, level);
    }

    // ── Door lock ─────────────────────────────────────────────────────────────

    public Map<String, Object> doorLock(String nodeHex, boolean lock) {
        int seq = nextSeq();
        List<String> hexBytes = zwaveProtocol.createZipPacket(Map.of(
                "header", Map.of(
                        "p1", Map.of("ACK_REQUEST_BIT_MASK_V2", true),
                        "p2", Map.of("SECURE_ORIGIN_BIT_MASK_V2", true, "Z_WAVE_CMD_INCLUDED_BIT_MASK_V2", true),
                        "seq", seq),
                "class",        "DOOR_LOCK",
                "cmd",          "DOOR_LOCK_OPERATION_SET",
                "doorLockMode", lock ? "ff" : "00"));
        Map<String, Object> resp = zwaveInterface.sendAndReceive(
                hexListToBytes(hexBytes), zwaveInterface.nodeIpFromHex(nodeHex), seq);
        return responseToMap(resp, lock ? "locked" : "unlocked");
    }

    // ── User codes (pincodes) ─────────────────────────────────────────────────

    /**
     * Request a user code from slot {@code slot} and wait for the USER_CODE_REPORT.
     */
    public CompletableFuture<Map<String, Object>> getUserCode(String nodeHex, int slot) {
        int seq = nextSeq();
        List<String> hexBytes = zwaveProtocol.createZipPacket(Map.of(
                "header", Map.of(
                        "p1", Map.of("ACK_REQUEST_BIT_MASK_V2", true),
                        "p2", Map.of("SECURE_ORIGIN_BIT_MASK_V2", true, "Z_WAVE_CMD_INCLUDED_BIT_MASK_V2", true),
                        "seq", seq),
                "class",          "USER_CODE",
                "cmd",            "USER_CODE_GET",
                "userIdentifier", String.format("%02x", slot)));

        Predicate<Map<String, Object>> matcher = pkt -> {
            Map<String, Object> zwCmd = getZwaveCmd(pkt);
            return zwCmd != null
                    && "USER_CODE".equals(zwCmd.get("class"))
                    && "USER_CODE_REPORT".equals(zwCmd.get("command"));
        };

        return zwaveInterface.sendAndWait(hexListToBytes(hexBytes),
                        zwaveInterface.nodeIpFromHex(nodeHex), matcher)
                .orTimeout(15, TimeUnit.SECONDS)
                .thenApply(pkt -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    Map<String, Object> fields = getZwaveCmdFields(pkt);
                    if (fields != null) m.putAll(fields);
                    return m;
                });
    }

    /**
     * Set a user code in a slot.
     */
    public Map<String, Object> setUserCode(String nodeHex, int slot, String code) {
        int seq = nextSeq();
        List<String> hexBytes = zwaveProtocol.createZipPacket(Map.of(
                "header", Map.of(
                        "p1", Map.of("ACK_REQUEST_BIT_MASK_V2", true),
                        "p2", Map.of("SECURE_ORIGIN_BIT_MASK_V2", true, "Z_WAVE_CMD_INCLUDED_BIT_MASK_V2", true),
                        "seq", seq),
                "class",          "USER_CODE",
                "cmd",            "USER_CODE_SET",
                "userIdentifier", String.format("%02x", slot),
                "userIdStatus",   "01",
                "userCode",       code));
        Map<String, Object> resp = zwaveInterface.sendAndReceive(
                hexListToBytes(hexBytes), zwaveInterface.nodeIpFromHex(nodeHex), seq);
        return responseToMap(resp, "ok");
    }

    /**
     * Clear a user code slot.
     */
    public Map<String, Object> deleteUserCode(String nodeHex, int slot) {
        int seq = nextSeq();
        List<String> hexBytes = zwaveProtocol.createZipPacket(Map.of(
                "header", Map.of(
                        "p1", Map.of("ACK_REQUEST_BIT_MASK_V2", true),
                        "p2", Map.of("SECURE_ORIGIN_BIT_MASK_V2", true, "Z_WAVE_CMD_INCLUDED_BIT_MASK_V2", true),
                        "seq", seq),
                "class",          "USER_CODE",
                "cmd",            "USER_CODE_SET",
                "userIdentifier", String.format("%02x", slot),
                "userIdStatus",   "00"));
        Map<String, Object> resp = zwaveInterface.sendAndReceive(
                hexListToBytes(hexBytes), zwaveInterface.nodeIpFromHex(nodeHex), seq);
        return responseToMap(resp, "deleted");
    }

    // ── Thermostat ────────────────────────────────────────────────────────────

    /**
     * @param modeHex  "00"=off, "01"=heat, "02"=cool, "03"=auto
     */
    public Map<String, Object> thermostatMode(String nodeHex, String modeHex) {
        int seq = nextSeq();
        List<String> hexBytes = zwaveProtocol.createZipPacket(Map.of(
                "header", Map.of(
                        "p1", Map.of("ACK_REQUEST_BIT_MASK_V2", true),
                        "p2", Map.of("SECURE_ORIGIN_BIT_MASK_V2", true, "Z_WAVE_CMD_INCLUDED_BIT_MASK_V2", true),
                        "seq", seq),
                "class", "THERMOSTAT_MODE",
                "cmd",   "THERMOSTAT_MODE_SET",
                "level", modeHex));
        Map<String, Object> resp = zwaveInterface.sendAndReceive(
                hexListToBytes(hexBytes), zwaveInterface.nodeIpFromHex(nodeHex), seq);
        return responseToMap(resp, modeHex);
    }

    public Map<String, Object> thermostatSetpoint(String nodeHex, double celsius, boolean heat) {
        int seq  = nextSeq();
        // level2: precision=0, scale=0 (Celsius), size=1 → 0x09
        String setpointType = heat ? "01" : "02";
        int    tempInt      = (int) Math.round(celsius);
        List<String> hexBytes = zwaveProtocol.createZipPacket(Map.of(
                "header", Map.of(
                        "p1", Map.of("ACK_REQUEST_BIT_MASK_V2", true),
                        "p2", Map.of("SECURE_ORIGIN_BIT_MASK_V2", true, "Z_WAVE_CMD_INCLUDED_BIT_MASK_V2", true),
                        "seq", seq),
                "class",  "THERMOSTAT_SETPOINT",
                "cmd",    "THERMOSTAT_SETPOINT_SET",
                "level",  setpointType,
                "level2", "09",
                "value1", String.format("%02x", tempInt & 0xFF),
                "value2", "00"));
        Map<String, Object> resp = zwaveInterface.sendAndReceive(
                hexListToBytes(hexBytes), zwaveInterface.nodeIpFromHex(nodeHex), seq);
        return responseToMap(resp, celsius);
    }

    // ── Device command dispatcher ─────────────────────────────────────────────

    /**
     * Routes a per-device API command to the correct Z-Wave method.
     */
    public Map<String, Object> handleDeviceCommand(
            Device dev, String cmd, String subId, String method, Map<String, Object> body) {

        String node = dev.getNode();
        if (node == null) return Map.of("error", "device has no node address");

        return switch (cmd) {

            case "lock" -> {
                if ("POST".equals(method)) {
                    String val = strB(body, "value");
                    yield doorLock(node, "lock".equals(val) || "true".equals(val) || "on".equals(val));
                }
                yield Map.of("status", "ok", "locked", getAttr(dev, "status", "value"));
            }

            case "pincode" -> {
                int slot = subId != null ? safeInt(subId) : intB(body, "slot");
                if ("GET".equals(method)) {
                    try { yield getUserCode(node, slot).get(15, TimeUnit.SECONDS); }
                    catch (Exception e) { yield Map.of("error", e.getMessage()); }
                }
                if ("POST".equals(method))   yield setUserCode(node, slot, strB(body, "code"));
                if ("DELETE".equals(method)) yield deleteUserCode(node, slot);
                yield Map.of("error", "method not allowed for pincode");
            }

            case "poll_pincodes" ->
                Map.of("status", "ok", "pincodes",
                        dev.getPincodes() != null ? dev.getPincodes() : Map.of());

            case "lock_time" -> {
                if ("POST".equals(method)) {
                    configTime(node);
                    yield Map.of("status", "ok");
                }
                yield Map.of("status", "ok", "note", "time params not cached");
            }

            case "switch" -> {
                if ("POST".equals(method)) {
                    String val = strB(body, "value");
                    yield switchBinary(node, "on".equals(val) || "true".equals(val));
                }
                yield Map.of("status", "ok", "value", getAttr(dev, "status", "value"));
            }

            case "level" -> {
                if ("POST".equals(method)) yield switchMultilevel(node, intB(body, "value"));
                yield Map.of("status", "ok", "value", getAttr(dev, "status", "value"));
            }

            case "thermostat" -> {
                if ("POST".equals(method)) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    Object heat    = body.get("heat");
                    Object cool    = body.get("cool");
                    String modeStr = strB(body, "mode");
                    if (heat != null) result.putAll(thermostatSetpoint(node, toDouble(heat), true));
                    if (cool != null) result.putAll(thermostatSetpoint(node, toDouble(cool), false));
                    if (modeStr != null) {
                        String modeHex = switch (modeStr) {
                            case "heat" -> "01";
                            case "cool" -> "02";
                            case "auto" -> "03";
                            case "off"  -> "00";
                            default     -> null;
                        };
                        if (modeHex != null) result.putAll(thermostatMode(node, modeHex));
                    }
                    if (!result.isEmpty()) yield result;
                    yield Map.of("error", "thermostat POST requires at least one of: heat, cool, mode");
                }
                yield Map.of("status", "ok",
                        "heat", getAttr(dev, "setpoint_heat", "value"),
                        "cool", getAttr(dev, "setpoint_cool", "value"),
                        "mode", getAttr(dev, "mode",          "value"));
            }

            case "setup" -> {
                if ("POST".equals(method)) {
                    try {
                        int nodeInt = Integer.parseInt(
                                node.toLowerCase().replace("0x", ""), 16);
                        setup(nodeInt);
                    } catch (Exception e) {
                        yield Map.of("error", e.getMessage());
                    }
                    yield Map.of("status", "ok");
                }
                yield Map.of("error", "use POST for setup");
            }

            default -> Map.of("error", "unhandled command: " + method + " " + cmd);
        };
    }

    // ── Device setup (after inclusion) ───────────────────────────────────────

    public void setup(int nodeId) {
        String nodeHex = String.format("0x%02X", nodeId);
        log.info("Setting up new Z-Wave node {} ({})", nodeId, nodeHex);

        Optional<Device> existing = deviceService.findByNode(nodeHex);
        if (existing.isPresent()) {
            log.debug("Node {} already in DB — skipping setup", nodeHex);
            return;
        }

        Device dev = new Device();
        dev.setProtocol("zwave");
        dev.setNode(nodeHex);
        deviceService.save(dev);
        log.info("Placeholder device created for node {}", nodeHex);

        // Kick off interview immediately for awake nodes (switches, dimmers, etc.).
        // Sleeping nodes won't respond until they wake up, but the GETs are queued
        // in the mailbox and will be processed on the next WAKE_UP_NOTIFICATION.
        interview(nodeId);
    }

    // ── Device state parsing ──────────────────────────────────────────────────

    public Map<String, Object> parseDevice(String id, Device dev) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",           id);
        m.put("protocol",     "zwave");
        m.put("name",         dev.getName());
        m.put("node",         dev.getNode());
        m.put("type",          dev.getType());
        m.put("manufacturer",  dev.getManufacturer());
        m.put("manufacturerId",  dev.getManufacturerId());
        m.put("productTypeId", dev.getProductTypeId());
        m.put("modelId",       dev.getModelId());
        m.put("descriptor",       dev.getDescriptor());
        m.put("descriptorSource", dev.getDescriptorSource());
        m.put("status",           getAttr(dev, "status", "value"));
        m.put("battery",          getAttr(dev, "battery", "value"));
        return m;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> sendNetworkMgmtFrame(String command,
                                                      Map<String, Object> params,
                                                      boolean blocking) {
        int seq = nextSeq();
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("header", Map.of(
                "p1", Map.of("ACK_REQUEST_BIT_MASK_V2", true),
                "p2", Map.of("SECURE_ORIGIN_BIT_MASK_V2", true, "Z_WAVE_CMD_INCLUDED_BIT_MASK_V2", true),
                "seq", seq));
        options.put("class", "NETWORK_MANAGEMENT_INCLUSION");
        options.put("cmd",   command);
        options.putAll(params);

        List<String> hexBytes = zwaveProtocol.createZipPacket(options);
        if (blocking) {
            Map<String, Object> resp = zwaveInterface.sendAndReceive(
                    hexListToBytes(hexBytes), ZIPGATEWAY_CTRL_IP, seq);
            return responseToMap(resp, command);
        } else {
            zwaveInterface.send(hexListToBytes(hexBytes), ZIPGATEWAY_CTRL_IP);
            return Map.of("status", "sent", "command", command);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> responseToMap(Map<String, Object> resp, Object defaultResult) {
        if (resp == null) return Map.of("status", "timeout");
        // Header lives at resp["data"]["header"], not at the top level.
        Object dataObj = resp.get("data");
        Map<String, Object> data = (dataObj instanceof Map) ? (Map<String, Object>) dataObj : null;
        Map<String, Object> props1 = ZWaveInterface.getProperties1(data);
        if (props1 != null) {
            if (Boolean.TRUE.equals(props1.get("NACK_QUEUE_FULL_BIT_MASK_V2")))
                return Map.of("status", "error", "reason", "queue_full");
            if (Boolean.TRUE.equals(props1.get("NACK_WAITING_BIT_MASK_V2")))
                return Map.of("status", "queued");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ok");
        m.put("result", defaultResult);
        return m;
    }

    /** Extract the Z-Wave command map from a parsed ZIP frame. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> getZwaveCmd(Map<String, Object> parsed) {
        if (parsed == null) return null;
        Object data = parsed.get("data");
        if (!(data instanceof Map)) return null;
        Object payload = ((Map<?, ?>) data).get("payload");
        return (payload instanceof Map) ? (Map<String, Object>) payload : null;
    }

    /** Extract the decoded fields from a parsed ZIP frame's Z-Wave command. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> getZwaveCmdFields(Map<String, Object> parsed) {
        Map<String, Object> zwCmd = getZwaveCmd(parsed);
        if (zwCmd == null) return null;
        Object p = zwCmd.get("payload");
        return (p instanceof Map) ? (Map<String, Object>) p : null;
    }

    private static byte[] hexListToBytes(List<String> hexList) {
        byte[] bytes = new byte[hexList.size()];
        for (int i = 0; i < hexList.size(); i++)
            bytes[i] = (byte) Integer.parseUnsignedInt(hexList.get(i), 16);
        return bytes;
    }

    private Object getAttr(Device dev, String cluster, String attr) {
        return dev.getAttributes() != null
                ? Optional.ofNullable(dev.getAttributes().get(cluster))
                           .map(m -> m.get(attr))
                           .orElse(null)
                : null;
    }

    private int seqCounter = 0;
    private synchronized int nextSeq() {
        seqCounter = (seqCounter + 1) & 0xFF;
        return seqCounter;
    }

    // ── Node-info seq tracking ────────────────────────────────────────────────

    private record PendingSeq(String nodeHex, long insertedAt) {}

    /** Maps seq of COMMAND_NODE_INFO_CACHED_GET → pending entry (nodeHex + timestamp). */
    private final java.util.concurrent.ConcurrentHashMap<Integer, PendingSeq> pendingNodeInfoSeq =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Tracks nodes with an interview currently in progress (nodeHex → start timestamp ms). */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> pendingInterviews =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Called by ZWaveReportHandler to resolve node from COMMAND_NODE_INFO_CACHED_REPORT seq-no. */
    public String claimNodeInfoSeq(int seq) {
        PendingSeq entry = pendingNodeInfoSeq.remove(seq);
        return entry != null ? entry.nodeHex() : null;
    }

    /** Called by ZWaveReportHandler once a descriptor has been applied. */
    public void markInterviewComplete(String nodeHex) {
        pendingInterviews.remove(nodeHex);
    }

    /** Evicts pending seq entries older than 60 s and stale interview entries older than 10 min. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000)
    public void evictStalePendingSeqs() {
        long seqCutoff = System.currentTimeMillis() - 60_000;
        pendingNodeInfoSeq.entrySet().removeIf(e -> {
            if (e.getValue().insertedAt() < seqCutoff) {
                log.warn("Evicting stale pending seq={} node={}", e.getKey(), e.getValue().nodeHex());
                return true;
            }
            return false;
        });

        long interviewCutoff = System.currentTimeMillis() - 600_000;
        pendingInterviews.entrySet().removeIf(e -> {
            if (e.getValue() < interviewCutoff) {
                log.warn("Evicting stale pending interview for node={}", e.getKey());
                return true;
            }
            return false;
        });
    }

    private String strB(Map<String, Object> m, String key) {
        if (m == null) return null;
        Object v = m.get(key);
        return v != null ? v.toString() : null;
    }

    private int intB(Map<String, Object> m, String key) {
        if (m == null) return 0;
        Object v = m.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) { try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {} }
        return 0;
    }

    private int safeInt(String s) {
        if (s == null) return 0;
        try { return Integer.parseInt(s); } catch (NumberFormatException ignored) { return 0; }
    }

    private double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) { try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {} }
        return 0.0;
    }

    // ── Interview ─────────────────────────────────────────────────────────────

    /**
     * Fire-and-forget interview: sends MANUFACTURER_SPECIFIC_GET and
     * NODE_INFO_CACHED_GET, then returns immediately.
     *
     * Responses arrive asynchronously via UnsolicitedListener → ZWaveReportHandler,
     * which accumulates data, resolves the descriptor, and runs init tasks.
     * Works for both awake nodes (reports back in ms) and sleeping nodes
     * (commands queued in mailbox, reports arrive on next wake-up).
     */
    public Map<String, Object> interview(int nodeId) {
        String nodeHex = String.format("0x%02X", nodeId);

        Long started = pendingInterviews.get(nodeHex);
        if (started != null && System.currentTimeMillis() - started < 600_000) {
            log.debug("Interview already in progress for node {} (started {} ms ago) — skipping",
                    nodeHex, System.currentTimeMillis() - started);
            return Map.of("status", "pending", "node", nodeHex);
        }
        pendingInterviews.put(nodeHex, System.currentTimeMillis());

        log.info("Interview started (fire-and-forget) for node {}", nodeHex);

        int seq1 = nextSeq();
        List<String> mfrBytes = zwaveProtocol.createZipPacket(Map.of(
                "header", Map.of(
                        "p1", Map.of("ACK_REQUEST_BIT_MASK_V2", true),
                        "p2", Map.of("SECURE_ORIGIN_BIT_MASK_V2", true, "Z_WAVE_CMD_INCLUDED_BIT_MASK_V2", true),
                        "seq", seq1),
                "class", "MANUFACTURER_SPECIFIC",
                "cmd",   "MANUFACTURER_SPECIFIC_GET"));
        zwaveInterface.send(hexListToBytes(mfrBytes), zwaveInterface.nodeIpFromHex(nodeHex));

        int seq2 = nextSeq();
        int nodeInt = Integer.parseUnsignedInt(nodeHex.replace("0x", "").replace("0X", ""), 16);
        List<String> niBytes = zwaveProtocol.createZipPacket(Map.of(
                "header", Map.of(
                        "p1", Map.of("ACK_REQUEST_BIT_MASK_V2", true),
                        "p2", Map.of("SECURE_ORIGIN_BIT_MASK_V2", true, "Z_WAVE_CMD_INCLUDED_BIT_MASK_V2", true),
                        "seq", seq2),
                "class",  "NETWORK_MANAGEMENT_PROXY",
                "cmd",    "COMMAND_NODE_INFO_CACHED_GET",
                "nodeId", String.format("%02x", nodeInt),
                "seqNo",  seq2));
        pendingNodeInfoSeq.put(seq2, new PendingSeq(nodeHex, System.currentTimeMillis()));
        zwaveInterface.send(hexListToBytes(niBytes), ZIPGATEWAY_CTRL_IP);

        log.debug("Interview GETs sent for node {} (seq mfr={} ni={})", nodeHex, seq1, seq2);
        return Map.of("status", "pending", "node", nodeHex);
    }

    /**
     * Applies a resolved descriptor to a device and runs all associated init tasks:
     * association bindings, interview GET commands, configuration parameters, and
     * init functions (poll-pincodes, config-time). Finishes with
     * WAKE_UP_NO_MORE_INFORMATION so sleeping nodes can return to sleep promptly.
     *
     * Called by ZWaveReportHandler once it has resolved a descriptor from
     * incoming MANUFACTURER_SPECIFIC_REPORT or NODE_INFO_CACHED_REPORT.
     */
    public void runInitTasks(String nodeHex, ZWaveDescriptor desc, Device dev) {
        log.info("Running init tasks for node {} — descriptor={}", nodeHex, desc.getType());

        for (int group : desc.lifelineGroups()) {
            try { associationSet(nodeHex, group, 1); }
            catch (Exception e) { log.warn("Association group {} failed", group, e); }
        }

        for (ZWaveDescriptor.InterviewStep step : desc.getInterview()) {
            try { getReport(nodeHex, step.getCommandClass(), step.getCmd(), step.getData()); }
            catch (Exception e) { log.warn("Interview {}/{} failed", step.getCommandClass(), step.getCmd(), e); }
        }

        for (ZWaveDescriptor.ConfigParam conf : desc.getConfiguration()) {
            try {
                if (conf.getValue() != null) configurationSet(nodeHex, conf.getParam(), conf.getValue());
                else                         configurationDefault(nodeHex, conf.getParam());
            } catch (Exception e) { log.warn("Config param {} failed", conf.getParam(), e); }
        }

        for (ZWaveDescriptor.InitTask task : desc.getInit()) {
            try {
                switch (task.getFunction()) {
                    case "poll-pincodes" -> pollPincodes(dev, task.getParams());
                    case "config-time"   -> configTime(nodeHex);
                    default              -> log.debug("Unknown init task: {}", task.getFunction());
                }
            } catch (Exception e) { log.warn("Init task {} failed", task.getFunction(), e); }
        }

        wakeUpNoMoreInformation(nodeHex);
    }

    /**
     * Sends WAKE_UP_NO_MORE_INFORMATION to a node, allowing it to return to sleep.
     * Should be called after all commands for a wake-up window have been queued.
     */
    public void wakeUpNoMoreInformation(String nodeHex) {
        int seq = nextSeq();
        List<String> hexBytes = zwaveProtocol.createZipPacket(Map.of(
                "header", Map.of(
                        "p1", Map.of("ACK_REQUEST_BIT_MASK_V2", true),
                        "p2", Map.of("SECURE_ORIGIN_BIT_MASK_V2", true, "Z_WAVE_CMD_INCLUDED_BIT_MASK_V2", true),
                        "seq", seq),
                "class", "WAKE_UP",
                "cmd",   "WAKE_UP_NO_MORE_INFORMATION"));
        zwaveInterface.send(hexListToBytes(hexBytes), zwaveInterface.nodeIpFromHex(nodeHex));
        log.debug("WAKE_UP_NO_MORE_INFORMATION sent to {}", nodeHex);
    }

    // ── Association / Configuration / Time / Pincodes ─────────────────────────

    /**
     * Binds an association group on the node to the controller (node 1).
     * Mirrors association-set in zwave/controller.clj.
     */
    public Map<String, Object> associationSet(String nodeHex, int group, int target) {
        int seq = nextSeq();
        String groupHex  = String.format("%02x", group);
        String targetHex = String.format("%02x", target);
        List<String> hexBytes = zwaveProtocol.createZipPacket(Map.of(
                "header", Map.of(
                        "p1", Map.of("ACK_REQUEST_BIT_MASK_V2", true),
                        "p2", Map.of("SECURE_ORIGIN_BIT_MASK_V2", true, "Z_WAVE_CMD_INCLUDED_BIT_MASK_V2", true),
                        "seq", seq),
                "class",              "ASSOCIATION",
                "cmd",                "ASSOCIATION_SET",
                "groupingIdentifier", groupHex,
                "nodeId1",            targetHex,
                "nodeId2",            targetHex));
        String destIp = zwaveInterface.nodeIpFromHex(nodeHex);
        try {
            Map<String, Object> res = zwaveInterface.sendAndWait(
                    hexListToBytes(hexBytes), destIp,
                    p -> "Z/IP".equals(p.get("class")) && "ZIP_PACKET".equals(p.get("command")))
                    .get(30, TimeUnit.SECONDS);
            log.debug("Association group {} target {} set on node {}", group, target, nodeHex);
            return res != null ? res : Map.of("status", "ok");
        } catch (Exception e) {
            log.warn("associationSet node={} group={} target={}", nodeHex, group, target, e);
            return Map.of("error", e.getMessage());
        }
    }

    /**
     * Sends a GET command and waits for any response. The report frame is
     * forwarded to ZWaveReportHandler via the unsolicited listener for DB updates.
     * Mirrors get-report in zwave/controller.clj.
     */
    public void getReport(String nodeHex, String commandClass, String cmd, List<String> data) {
        int seq = nextSeq();
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("header", Map.of(
                "p1", Map.of("ACK_REQUEST_BIT_MASK_V2", true),
                "p2", Map.of("SECURE_ORIGIN_BIT_MASK_V2", true, "Z_WAVE_CMD_INCLUDED_BIT_MASK_V2", true),
                "seq", seq));
        opts.put("class", commandClass);
        opts.put("cmd",   cmd);
        if (data != null && !data.isEmpty()) opts.put("data", data);

        List<String> hexBytes = zwaveProtocol.createZipPacket(opts);
        zwaveInterface.send(hexListToBytes(hexBytes), zwaveInterface.nodeIpFromHex(nodeHex));
        log.debug("getReport {}/{} sent to {}", commandClass, cmd, nodeHex);
    }

    /**
     * Sets a configuration parameter on a node.
     * Mirrors configuration-set in zwave/controller.clj.
     *
     * @param param  parameter number as hex string, e.g. "02"
     * @param value  list of hex byte strings, e.g. ["FF"]
     */
    public void configurationSet(String nodeHex, String param, List<String> value) {
        int seq = nextSeq();
        List<String> hexBytes = zwaveProtocol.createZipPacket(Map.of(
                "header", Map.of(
                        "p1", Map.of("ACK_REQUEST_BIT_MASK_V2", true),
                        "p2", Map.of("SECURE_ORIGIN_BIT_MASK_V2", true, "Z_WAVE_CMD_INCLUDED_BIT_MASK_V2", true),
                        "seq", seq),
                "class",     "CONFIGURATION",
                "cmd",       "CONFIGURATION_SET",
                "parameter", param,
                "default",   false,
                "value",     value));
        zwaveInterface.send(hexListToBytes(hexBytes), zwaveInterface.nodeIpFromHex(nodeHex));
        log.debug("Configuration param {} set on node {}", param, nodeHex);
    }

    /**
     * Resets a configuration parameter to its factory default.
     * Mirrors configuration-default in zwave/controller.clj.
     */
    public void configurationDefault(String nodeHex, String param) {
        int seq = nextSeq();
        List<String> hexBytes = zwaveProtocol.createZipPacket(Map.of(
                "header", Map.of(
                        "p1", Map.of("ACK_REQUEST_BIT_MASK_V2", true),
                        "p2", Map.of("SECURE_ORIGIN_BIT_MASK_V2", true, "Z_WAVE_CMD_INCLUDED_BIT_MASK_V2", true),
                        "seq", seq),
                "class",     "CONFIGURATION",
                "cmd",       "CONFIGURATION_SET",
                "parameter", param,
                "default",   true));
        zwaveInterface.send(hexListToBytes(hexBytes), zwaveInterface.nodeIpFromHex(nodeHex));
        log.debug("Configuration param {} reset to default on node {}", param, nodeHex);
    }

    /**
     * Sets timezone offset and current UTC time on the node.
     * Mirrors config-time in zwave/controller.clj.
     */
    public void configTime(String nodeHex) {
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(platformService.getTimezone());
        } catch (Exception e) {
            log.warn("configTime: invalid timezone '{}', using system default", platformService.getTimezone());
            zoneId = ZoneId.systemDefault();
        }
        setTimeOffset(nodeHex, zoneId);
        setTimeParams(nodeHex);
    }

    /**
     * Sends TIME_OFFSET_SET: standard UTC offset + DST transition rules.
     * Mirrors set-time-offset in zwave/controller.clj.
     */
    private void setTimeOffset(String nodeHex, ZoneId zoneId) {
        ZoneRules rules = zoneId.getRules();
        ZoneOffset stdOffset = rules.getStandardOffset(Instant.now());

        int totalSec  = stdOffset.getTotalSeconds();
        boolean signTzo = totalSec < 0;
        int absSec    = Math.abs(totalSec);
        int hourTzo   = absSec / 3600;
        int minuteTzo = (absSec % 3600) / 60;
        int level     = signTzo ? (0x80 | hourTzo) : hourTzo;

        // DST transition rules for the current year (0 if zone has no DST)
        int monthStartDst = 0, dayStartDst = 0, hourStartDst = 0;
        int monthEndDst   = 0, dayEndDst   = 0, hourEndDst   = 0;
        int minuteOffsetDST = 0;
        boolean signOffsetDST = false;

        int year = LocalDate.now().getYear();
        for (ZoneOffsetTransitionRule rule : rules.getTransitionRules()) {
            ZoneOffsetTransition tx  = rule.createTransition(year);
            LocalDateTime        ldt = tx.getDateTimeBefore();
            int diffSec = tx.getOffsetAfter().getTotalSeconds() - tx.getOffsetBefore().getTotalSeconds();
            if (diffSec > 0) {                    // spring forward = DST start
                monthStartDst   = ldt.getMonthValue();
                dayStartDst     = ldt.getDayOfMonth();
                hourStartDst    = ldt.getHour();
                signOffsetDST   = false;
                minuteOffsetDST = diffSec / 60;
            } else {                               // fall back = DST end
                monthEndDst = ldt.getMonthValue();
                dayEndDst   = ldt.getDayOfMonth();
                hourEndDst  = ldt.getHour();
            }
        }
        int level2 = signOffsetDST ? (0x80 | minuteOffsetDST) : minuteOffsetDST;

        int seq = nextSeq();
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("header", Map.of(
                "p1", Map.of("ACK_REQUEST_BIT_MASK_V2", true),
                "p2", Map.of("SECURE_ORIGIN_BIT_MASK_V2", true, "Z_WAVE_CMD_INCLUDED_BIT_MASK_V2", true),
                "seq", seq));
        opts.put("class",         "TIME");
        opts.put("cmd",           "TIME_OFFSET_SET");
        opts.put("level",         String.format("%02x", level));
        opts.put("minuteTzo",     String.format("%02x", minuteTzo));
        opts.put("level2",        String.format("%02x", level2));
        opts.put("monthStartDst", String.format("%02x", monthStartDst));
        opts.put("dayStartDst",   String.format("%02x", dayStartDst));
        opts.put("hourStartDst",  String.format("%02x", hourStartDst));
        opts.put("monthEndDst",   String.format("%02x", monthEndDst));
        opts.put("dayEndDst",     String.format("%02x", dayEndDst));
        opts.put("hourEndDst",    String.format("%02x", hourEndDst));

        List<String> hexBytes = zwaveProtocol.createZipPacket(opts);
        zwaveInterface.send(hexListToBytes(hexBytes), zwaveInterface.nodeIpFromHex(nodeHex));
        log.debug("TIME_OFFSET_SET sent to {}", nodeHex);
    }

    /**
     * Sends TIME_PARAMETERS_SET with the current UTC time.
     * Mirrors set-time-params in zwave/controller.clj.
     */
    private void setTimeParams(String nodeHex) {
        ZonedDateTime now  = ZonedDateTime.now(ZoneOffset.UTC);
        int           year = now.getYear();
        int seq = nextSeq();
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("header", Map.of(
                "p1", Map.of("ACK_REQUEST_BIT_MASK_V2", true),
                "p2", Map.of("SECURE_ORIGIN_BIT_MASK_V2", true, "Z_WAVE_CMD_INCLUDED_BIT_MASK_V2", true),
                "seq", seq));
        opts.put("class",      "TIME_PARAMETERS");
        opts.put("cmd",        "TIME_PARAMETERS_SET");
        opts.put("year1",      String.format("%02x", (year >> 8) & 0xFF));
        opts.put("year2",      String.format("%02x", year & 0xFF));
        opts.put("month",      String.format("%02x", now.getMonthValue()));
        opts.put("day",        String.format("%02x", now.getDayOfMonth()));
        opts.put("hourUtc",    String.format("%02x", now.getHour()));
        opts.put("minuteUtc",  String.format("%02x", now.getMinute()));
        opts.put("secondUtc",  String.format("%02x", now.getSecond()));

        List<String> hexBytes = zwaveProtocol.createZipPacket(opts);
        zwaveInterface.send(hexListToBytes(hexBytes), zwaveInterface.nodeIpFromHex(nodeHex));
        log.debug("TIME_PARAMETERS_SET sent to {}", nodeHex);
    }

    /**
     * Asynchronously polls all PIN code slots in the range defined by the init task params.
     * Mirrors poll-pincodes in zwave/controller.clj.
     */
    private void pollPincodes(Device dev, Map<String, Object> params) {
        String nodeHex = dev.getNode();
        int start = params != null && params.containsKey("start") ? ((Number) params.get("start")).intValue() : 1;
        int end   = params != null && params.containsKey("end")   ? ((Number) params.get("end")).intValue()   : 10;
        log.info("pollPincodes: node {} slots {}-{}", nodeHex, start, end);
        // Chain slot requests sequentially without blocking any thread.
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int slot = start; slot <= end; slot++) {
            final int s = slot;
            chain = chain.thenCompose(ignored ->
                getUserCode(nodeHex, s)
                    .orTimeout(15, TimeUnit.SECONDS)
                    .handle((r, ex) -> {
                        if (ex != null) log.warn("pollPincodes slot {} error", s, ex);
                        return null;
                    }));
        }
    }

}

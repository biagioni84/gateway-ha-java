package uy.plomo.gateway.zigbee;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import uy.plomo.gateway.descriptor.DeviceDescriptorService;
import uy.plomo.gateway.descriptor.ResolvedDescriptor;
import uy.plomo.gateway.descriptor.ZigbeeDescriptor;
import uy.plomo.gateway.device.Device;
import uy.plomo.gateway.device.DeviceService;
import uy.plomo.gateway.mqtt.MqttService;

import java.time.Instant;
import java.util.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Predicate;

/**
 * Zigbee business logic: inclusion/exclusion, device commands, user codes, schedules.
 *
 * Mirrors the active code in the legacy Clojure implementation.
 *
 * All cluster-command-wait calls use the hook system in ZigbeeInterface.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ZigbeeController {

    private static final String SEQ_NO       = "02";
    private static final int    DEFAULT_EP   = 1;
    private static final int    TIMEOUT_MS   = 30_000;
    private static final long   INTERVIEW_GUARD_MS = 600_000; // 10 minutes

    private final ZigbeeInterface         zigbeeInterface;
    private final ZigbeeReportHandler     reportHandler;
    private final DeviceService           deviceService;
    private final DeviceDescriptorService descriptorService;
    private final ObjectMapper            objectMapper;
    @Lazy private final MqttService       mqttService;

    /** Tracks devices with an interview in progress (ieeeAddr → start timestamp ms). */
    private final ConcurrentHashMap<String, Long> pendingInterviews = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        zigbeeInterface.setReportHandler(reportHandler);
    }

    /**
     * On startup, query the Z3Gateway device table and re-interview any device
     * not yet in the DB (or with no descriptor). Mirrors the Z-Wave NODE_LIST_GET
     * flow triggered by UnsolicitedListener.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!zigbeeInterface.isEnabled()) return;
        log.info("Requesting Zigbee device table on startup");
        // thenAcceptAsync: run on the common pool, not on the zigbee-timeout scheduler thread,
        // so that interview() blocking calls don't starve the timeout scheduler.
        zigbeeInterface.requestDeviceList().thenAcceptAsync(deviceMap -> {
            if (deviceMap.isEmpty()) {
                log.info("Zigbee device table: no devices found");
                return;
            }
            log.info("Zigbee device table: {} device(s) — {}", deviceMap.size(), deviceMap);
            deviceMap.forEach((ieeeAddr, nwkAddr) -> {
                try {
                    // Always persist the NWK address so interview() can use it for send
                    Device dev = deviceService.findByIeeeAddr(ieeeAddr).orElseGet(() -> {
                        Device d = new Device();
                        d.setId(UUID.randomUUID().toString());
                        d.setProtocol("zigbee");
                        d.setIeeeAddr(ieeeAddr);
                        return d;
                    });
                    dev.setNode(nwkAddr);
                    deviceService.save(dev);

                    boolean known = dev.getDescriptor() != null
                            && !"unknown".equals(dev.getDescriptor());
                    if (!known) {
                        log.info("Re-interviewing device {} nwk={} (no descriptor)", ieeeAddr, nwkAddr);
                        interview(ieeeAddr);
                    } else {
                        log.debug("Device {} already has descriptor — skipping", ieeeAddr);
                    }
                } catch (Exception e) {
                    log.warn("Startup handling failed for {}", ieeeAddr, e);
                }
            });
        });
    }

    // ── Network management ────────────────────────────────────────────────────

    public Map<String, Object> inclusion(String cmd) {
        return switch (cmd) {
            case "start" -> {
                try {
                    ZigbeeMessage r = zigbeeInterface
                            .sendCommandWait("plugin network-creator-security open-network",
                                    ZigbeeInterface.typeMatcher("inclusion_started"), TIMEOUT_MS)
                            .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    yield Map.of("status", "opened", "data", r.getRawData() != null ? r.getRawData() : "");
                } catch (Exception e) {
                    yield Map.of("status", "error", "message", e.getMessage());
                }
            }
            case "stop" -> {
                try {
                    ZigbeeMessage r = zigbeeInterface
                            .sendCommandWait("plugin network-creator-security close-network",
                                    ZigbeeInterface.typeMatcher("inclusion_finished"), TIMEOUT_MS)
                            .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    yield Map.of("status", "closed");
                } catch (Exception e) {
                    yield Map.of("status", "error", "message", e.getMessage());
                }
            }
            default -> Map.of("status", "error", "message", "Unknown cmd: " + cmd);
        };
    }

    // ── On/Off ────────────────────────────────────────────────────────────────

    public Map<String, Object> onOff(String node, boolean on, int ep) {
        String zcl = on ? "on" : "off";
        String cmd = "zcl on-off " + zcl + "\nsend " + node + " 1 " + ep;
        try {
            ZigbeeMessage r = zigbeeInterface
                    .sendCommandWait(cmd, ZigbeeInterface.frameMatcher(
                            node, "OnOff", "DefaultResponse", null), TIMEOUT_MS)
                    .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return Map.of("status", "ok", "result", zcl);
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    // ── Door lock ─────────────────────────────────────────────────────────────

    public Map<String, Object> doorLock(String node, boolean lock, int ep) {
        String cmdName = lock ? "LockDoor" : "UnlockDoor";
        String cmdId   = ZigbeeProtocol.doorlockServerCmdId(cmdName);
        String raw = buildRawCmd("0x0101", cmdId, "");
        Predicate<ZigbeeMessage> matcher = ZigbeeInterface.frameMatcher(
                node, "DoorLock",
                lock ? "LockDoorResponse" : "UnlockDoorResponse", null);

        try {
            clusterCommandWait(raw, node, ep, matcher);
            return Map.of("status", "ok", "result", lock ? "locked" : "unlocked");
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    // ── User codes ────────────────────────────────────────────────────────────

    public CompletableFuture<Map<String, Object>> getUserCode(String node, int slot, int ep) {
        String slotBytes = ZigbeeProtocol.leUint16(slot);
        String cmdId = ZigbeeProtocol.doorlockServerCmdId("GetPINCode");
        String raw = buildRawCmd("0x0101", cmdId, slotBytes);
        Predicate<ZigbeeMessage> matcher = ZigbeeInterface.frameMatcher(
                node, "DoorLock", "GetPINCodeResponse",
                Map.of("UserID", String.valueOf(slot)));

        return zigbeeInterface
                .sendCommandWait(raw + "\nsend " + node + " 1 " + ep, matcher, TIMEOUT_MS)
                .orTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .thenApply(msg -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    if (msg.getFields() != null) m.putAll(msg.getFields());
                    return m;
                });
    }

    public Map<String, Object> setUserCode(String node, int slot, String pin,
                                            String userType, int ep) {
        String slotBytes = ZigbeeProtocol.leUint16(slot);
        // userType: "00" = UnrestrictedUser, encode as uint8
        String userTypeHex = "00"; // default
        String payload = slotBytes + " 01 " + userTypeHex + " "
                + ZigbeeProtocol.octstring(pin);
        String cmdId = ZigbeeProtocol.doorlockServerCmdId("SetPINCode");
        String raw = buildRawCmd("0x0101", cmdId, payload);
        Predicate<ZigbeeMessage> matcher = ZigbeeInterface.frameMatcher(
                node, "DoorLock", "ProgrammingEventNotification",
                Map.of("UserID", String.valueOf(slot)));

        try {
            ZigbeeMessage r = clusterCommandWait(raw, node, ep, matcher);
            String status = r.field("EventCode");
            return Map.of("status", "ok", "eventCode", status != null ? status : "");
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    public Map<String, Object> deleteUserCode(String node, int slot, int ep) {
        String slotBytes = ZigbeeProtocol.leUint16(slot);
        String cmdId = ZigbeeProtocol.doorlockServerCmdId("ClearPINCode");
        String raw = buildRawCmd("0x0101", cmdId, slotBytes);
        Predicate<ZigbeeMessage> matcher = ZigbeeInterface.frameMatcher(
                node, "DoorLock", "ClearPINCodeResponse", null);

        try {
            clusterCommandWait(raw, node, ep, matcher);
            return Map.of("status", "ok", "result", "deleted");
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    // ── Schedule management ───────────────────────────────────────────────────

    public Map<String, Object> setWeekdayRestriction(
            String node, int slot, String dayMask,
            String startHour, String startMin, String endHour, String endMin, int ep) {

        String slotBytes = ZigbeeProtocol.leUint16(slot);
        String payload = "01 " + slotBytes + " " + dayMask + " "
                + startHour + " " + startMin + " " + endHour + " " + endMin;
        String cmdId = ZigbeeProtocol.doorlockServerCmdId("SetWeekdaySchedule");
        String raw = buildRawCmd("0x0101", cmdId, payload);
        Predicate<ZigbeeMessage> matcher = ZigbeeInterface.frameMatcher(
                node, "DoorLock", "ProgrammingEventNotification",
                Map.of("UserID", String.valueOf(slot)));

        try {
            clusterCommandWait(raw, node, ep, matcher);
            return Map.of("status", "ok");
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    public Map<String, Object> setYearDayRestriction(
            String node, int slot, long startEpoch, long endEpoch, int ep) {

        String slotBytes  = ZigbeeProtocol.leUint16(slot);
        String startBytes = ZigbeeProtocol.leUint32(startEpoch);
        String endBytes   = ZigbeeProtocol.leUint32(endEpoch);
        String payload = "00 " + slotBytes + " " + startBytes + " " + endBytes;
        String cmdId = ZigbeeProtocol.doorlockServerCmdId("SetYearDaySchedule");
        String raw = buildRawCmd("0x0101", cmdId, payload);
        Predicate<ZigbeeMessage> matcher = ZigbeeInterface.frameMatcher(
                node, "DoorLock", "ProgrammingEventNotification",
                Map.of("UserID", String.valueOf(slot)));

        try {
            clusterCommandWait(raw, node, ep, matcher);
            return Map.of("status", "ok");
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    public Map<String, Object> clearWeekdayRestriction(String node, int slot, int ep) {
        String slotBytes = ZigbeeProtocol.leUint16(slot);
        String cmdId = ZigbeeProtocol.doorlockServerCmdId("ClearWeekdaySchedule");
        String raw = buildRawCmd("0x0101", cmdId, "01 " + slotBytes);
        Predicate<ZigbeeMessage> matcher = ZigbeeInterface.frameMatcher(
                node, "DoorLock", "ClearWeekdayScheduleResponse", null);
        try {
            clusterCommandWait(raw, node, ep, matcher);
            return Map.of("status", "ok");
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    public Map<String, Object> clearYearDayRestriction(String node, int slot, int ep) {
        String slotBytes = ZigbeeProtocol.leUint16(slot);
        String cmdId = ZigbeeProtocol.doorlockServerCmdId("ClearYearDaySchedule");
        String raw = buildRawCmd("0x0101", cmdId, "00 " + slotBytes);
        Predicate<ZigbeeMessage> matcher = ZigbeeInterface.frameMatcher(
                node, "DoorLock", "ClearYearDayScheduleResponse", null);
        try {
            clusterCommandWait(raw, node, ep, matcher);
            return Map.of("status", "ok");
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    // ── Device command dispatcher ─────────────────────────────────────────────

    /**
     * Routes a per-device API command to the correct Zigbee method.
     * Mirrors handle-device-req in zigbee/controller.clj.
     */
    public Map<String, Object> handleDeviceCommand(
            Device dev, String cmd, String subId, String method, Map<String, Object> body) {

        String node = dev.getNode() != null ? dev.getNode() : dev.getIeeeAddr();
        if (node == null) return Map.of("error", "device has no node address");
        int ep = intB(body, "ep");
        if (ep == 0) ep = DEFAULT_EP;

        return switch (cmd) {

            case "on"  -> onOff(node, true,  ep);
            case "off" -> onOff(node, false, ep);

            case "switch" -> {
                if ("POST".equals(method)) {
                    String val = strB(body, "value");
                    yield onOff(node, "on".equals(val) || "true".equals(val), ep);
                }
                yield Map.of("status", "ok", "value", getAttr(dev, "OnOff", "OnOff"));
            }

            case "lock" -> {
                if ("POST".equals(method)) {
                    String val = strB(body, "value");
                    yield doorLock(node, "lock".equals(val) || "true".equals(val), ep);
                }
                yield Map.of("status", "ok", "locked", getAttr(dev, "DoorLock", "LockState"));
            }

            case "pincode" -> {
                int slot = subId != null ? safeInt(subId) : intB(body, "slot");
                if ("GET".equals(method)) {
                    try { yield getUserCode(node, slot, ep).get(TIMEOUT_MS, TimeUnit.MILLISECONDS); }
                    catch (Exception e) { yield Map.of("error", e.getMessage()); }
                }
                if ("POST".equals(method))
                    yield setUserCode(node, slot, strB(body, "code"), strB(body, "type"), ep);
                if ("DELETE".equals(method))
                    yield deleteUserCode(node, slot, ep);
                yield Map.of("error", "method not allowed for pincode");
            }

            case "poll_pincodes" ->
                Map.of("status", "ok", "pincodes",
                        dev.getPincodes() != null ? dev.getPincodes() : Map.of());

            default -> Map.of("error", "unhandled command: " + method + " " + cmd);
        };
    }

    // ── Device setup (interview) ──────────────────────────────────────────────

    /**
     * Query and register a newly-joined device.
     * Mirrors (interview node) in interface.clj.
     *
     * Synchronously reads Basic cluster for descriptor resolution, then returns
     * immediately. Bindings, attribute reads, and reporting config run async.
     */
    /** Called by ZigbeeReportHandler after a descriptor has been applied. */
    public void markInterviewComplete(String ieeeAddr) {
        pendingInterviews.remove(ieeeAddr);
    }

    /** Evicts stale interview guards older than 10 minutes. */
    @Scheduled(fixedDelay = 60_000)
    public void evictStaleInterviews() {
        long cutoff = System.currentTimeMillis() - INTERVIEW_GUARD_MS;
        pendingInterviews.entrySet().removeIf(e -> {
            if (e.getValue() < cutoff) {
                log.warn("Evicting stale pending interview for zigbee node={}", e.getKey());
                return true;
            }
            return false;
        });
    }

    public Map<String, Object> interview(String ieeeAddr) {
        Long started = pendingInterviews.get(ieeeAddr);
        if (started != null && System.currentTimeMillis() - started < INTERVIEW_GUARD_MS) {
            log.debug("Interview already in progress for {} (started {} ms ago) — skipping",
                    ieeeAddr, System.currentTimeMillis() - started);
            return Map.of("status", "pending", "node", ieeeAddr);
        }
        pendingInterviews.put(ieeeAddr, System.currentTimeMillis());
        log.info("Interviewing Zigbee node: {}", ieeeAddr);

        // 1. Find/create device so we can read the stored NWK address
        Device dev = deviceService.findByIeeeAddr(ieeeAddr).orElseGet(() -> {
            Device d = new Device();
            d.setId(UUID.randomUUID().toString());
            d.setProtocol("zigbee");
            d.setIeeeAddr(ieeeAddr);
            deviceService.save(d);
            return d;
        });
        // NWK short address is required by the Z3Gateway send command
        String sendAddr = dev.getNode() != null ? dev.getNode() : ieeeAddr;

        // 2. Read Basic cluster attributes for descriptor resolution
        Object mfrObj   = readAttribute(sendAddr, ieeeAddr, "Basic", "ManufacturerName", DEFAULT_EP);
        Object modelObj = readAttribute(sendAddr, ieeeAddr, "Basic", "ModelIdentifier",  DEFAULT_EP);
        String mfr   = mfrObj   != null ? mfrObj.toString()   : null;
        String model = modelObj != null ? modelObj.toString() : null;
        log.info("Node {} Basic: manufacturer={} model={}", ieeeAddr, mfr, model);

        // 3. Resolve descriptor
        Map<String, String> criteria = new LinkedHashMap<>();
        if (mfr   != null) criteria.put("manufacturer", mfr);
        if (model != null) criteria.put("modelId", model);
        Optional<ResolvedDescriptor> resolved = descriptorService.resolve("zigbee", criteria);
        ZigbeeDescriptor desc = resolved.map(r -> (ZigbeeDescriptor) r.content()).orElse(null);

        // 4. Update device fields
        if (mfr   != null) dev.setManufacturer(mfr);
        if (model != null) dev.setModelId(model);
        if (desc  != null) {
            dev.setType(desc.getType());
            dev.setDescriptor(resolved.get().file());
            dev.setDescriptorSource(resolved.get().origin());   // G4
            if (!desc.getFwdEvents().isEmpty()) dev.setFwdEvents(desc.getFwdEvents());
        } else {
            dev.setDescriptor("unknown");
        }
        deviceService.save(dev);

        // G5: publish INTERVIEW_COMPLETE when a descriptor was successfully resolved
        if (desc != null) {
            try {
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("type",    "zigbee");
                event.put("node-id", ieeeAddr);
                event.put("payload", Map.of(
                        "cmd",        "INTERVIEW_COMPLETE",
                        "descriptor", resolved.get().file(),
                        "source",     resolved.get().origin(),
                        "deviceType", desc.getType() != null ? desc.getType() : "unknown"));
                mqttService.publishEvent(objectMapper.writeValueAsString(event));
            } catch (Exception e) {
                log.warn("Failed to publish INTERVIEW_COMPLETE for node {}", ieeeAddr, e);
            }
        }

        // 5. Async: bindings + interview attribute reads + reporting
        if (desc != null) {
            ZigbeeDescriptor finalDesc = desc;
            Device           finalDev  = dev;
            CompletableFuture.runAsync(() -> runZigbeeSetup(sendAddr, finalDev, finalDesc));
        }

        return Map.of(
                "status",       "interviewing",
                "node",         ieeeAddr,
                "manufacturer", mfr   != null ? mfr   : "",
                "model",        model != null ? model : "",
                "type",         desc  != null && desc.getType() != null ? desc.getType() : "unknown",
                "descriptor",   dev.getDescriptor() != null ? dev.getDescriptor() : "unknown"
        );
    }

    // ── Interview helpers ─────────────────────────────────────────────────────

    /**
     * Read a single ZCL attribute from a node synchronously.
     * Sends {@code zcl global read}, waits for buffer ack, sends to node,
     * waits for ReadAttributesResponse, then decodes the value.
     */
    /**
     * @param sendAddr NWK short address (e.g. "0x96E4") — used for send command and frame matching
     * @param ieeeAddr IEEE EUI-64 — used only for log messages
     */
    private Object readAttribute(String sendAddr, String ieeeAddr,
                                 String clusterName, String attrName, int ep) {
        ZigbeeProtocol.ClusterAttr spec = ZigbeeProtocol.lookupAttr(clusterName, attrName);
        if (spec == null) {
            log.warn("No attribute spec for {}/{}", clusterName, attrName);
            return null;
        }
        String readCmd = "zcl global read 0x" + spec.clusterId() + " " + spec.attrId();
        Predicate<ZigbeeMessage> matcher =
                ZigbeeInterface.frameMatcher(sendAddr, clusterName, "ReadAttributesResponse", null);
        try {
            zigbeeInterface.sendCommandWait(readCmd, ZigbeeInterface.typeMatcher("buffer"), TIMEOUT_MS)
                    .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            ZigbeeMessage response = zigbeeInterface
                    .sendCommandWait("send " + sendAddr + " 1 " + ep, matcher, TIMEOUT_MS)
                    .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (response == null) return null;
            return ZigbeeProtocol.decodeReadAttributeValue(response.getRawPayload());
        } catch (Exception e) {
            Throwable cause = (e instanceof java.util.concurrent.ExecutionException) ? e.getCause() : e;
            if (cause instanceof TimeoutException) {
                log.debug("readAttribute {}/{} timed out for {} (device sleeping?)", clusterName, attrName, ieeeAddr);
            } else {
                log.warn("readAttribute {}/{} from {} failed", clusterName, attrName, ieeeAddr, e);
            }
            return null;
        }
    }

    /** Configure a ZDO binding on the device. */
    private void bindCluster(String node, String sep, String dep, String cluster) {
        String cmd = "zdo bind " + node + " " + sep + " " + dep + " " + cluster + " {} {}";
        try {
            zigbeeInterface.sendCommandWait(cmd, ZigbeeInterface.typeMatcher("zdo-bind-req"), TIMEOUT_MS)
                    .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            log.debug("Bind ok: {} sep={} dep={} cluster={}", node, sep, dep, cluster);
        } catch (Exception e) {
            log.warn("bindCluster {} {}/{}/{}", node, sep, dep, cluster, e);
        }
    }

    /** Push a ZCL attribute reporting configuration to the device. */
    private void configureReporting(String node, String sep, String dep,
                                    String clusterName, String attrName,
                                    int minTime, int maxTime, String delta) {
        ZigbeeProtocol.ClusterAttr spec = ZigbeeProtocol.lookupAttr(clusterName, attrName);
        if (spec == null) {
            log.warn("No attr spec for {}/{} — skipping reporting config", clusterName, attrName);
            return;
        }
        String cmd = "zcl global send-me-a-report 0x" + spec.clusterId()
                + " " + spec.attrId() + " " + spec.typeId()
                + " " + minTime + " " + maxTime
                + " {" + (delta != null && !delta.isBlank() ? delta : "01") + "}";
        try {
            zigbeeInterface.sendCommandWait(cmd, ZigbeeInterface.typeMatcher("buffer"), TIMEOUT_MS)
                    .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            zigbeeInterface.sendCommand("send " + node + " " + sep + " " + dep);
            log.debug("Reporting configured: {} {}/{}", node, clusterName, attrName);
        } catch (Exception e) {
            log.warn("configureReporting {} {}/{}", node, clusterName, attrName, e);
        }
    }

    /**
     * Run bindings, interview attribute reads, reporting config, and init tasks.
     * Called asynchronously after the device descriptor has been resolved.
     * Each step is individually guarded so a failure in one does not abort the rest.
     * Init tasks run as a non-blocking future chain; markInterviewComplete fires at the end.
     */
    private void runZigbeeSetup(String node, Device dev, ZigbeeDescriptor desc) {
        log.info("Running Zigbee setup for node {}", node);

        // Bindings
        for (ZigbeeDescriptor.Binding b : desc.getBindings()) {
            try { bindCluster(node, b.getSep(), b.getDep(), b.getCluster()); }
            catch (Exception e) { log.warn("Bind {} cluster={} failed", node, b.getCluster(), e); }
        }

        // Interview: read attributes and cache on device
        for (ZigbeeDescriptor.InterviewStep step : desc.getInterview()) {
            String cluster = step.getCluster();
            for (String attr : step.getAttributes()) {
                try {
                    Object value = readAttribute(node, dev.getIeeeAddr(), cluster, attr, DEFAULT_EP);
                    if (value != null) dev.setAttribute(cluster, attr, value);
                } catch (Exception e) { log.warn("Interview read {}/{} failed for {}", cluster, attr, node, e); }
            }
        }
        try { deviceService.save(dev); }
        catch (Exception e) { log.warn("Device save failed for {}", node, e); }

        // Reporting configuration
        for (ZigbeeDescriptor.ReportingConfig rc : desc.getReporting()) {
            try {
                configureReporting(node, rc.getSep(), rc.getDep(),
                        rc.getCluster(), rc.getAttribute(),
                        rc.getMinTime(), rc.getMaxTime(), rc.getDelta());
            } catch (Exception e) { log.warn("Reporting config {}/{} failed for {}", rc.getCluster(), rc.getAttribute(), node, e); }
        }

        // Init tasks — chained as non-blocking futures so no thread is held during slot polling
        CompletableFuture<Void> initChain = CompletableFuture.completedFuture(null);
        for (ZigbeeDescriptor.InitTask task : desc.getInit()) {
            initChain = initChain.thenCompose(v -> {
                try {
                    return switch (task.getFunction()) {
                        case "poll-pincodes" -> runPollPincodes(node, task.getParams());
                        default -> {
                            log.debug("Unknown Zigbee init task: {}", task.getFunction());
                            yield CompletableFuture.completedFuture(null);
                        }
                    };
                } catch (Exception e) {
                    log.warn("Init task {} failed for node {}", task.getFunction(), node, e);
                    return CompletableFuture.completedFuture(null);
                }
            });
        }
        initChain
                .thenRun(() -> {
                    log.info("Zigbee setup complete for node {}", node);
                    markInterviewComplete(dev.getIeeeAddr());
                })
                .exceptionally(ex -> {
                    log.warn("Zigbee init tasks error for node {}", node, ex);
                    markInterviewComplete(dev.getIeeeAddr());
                    return null;
                });
    }

    /**
     * Poll PIN code slots [start, end] as a non-blocking future chain.
     * Each slot waits for a GetPINCodeResponse; on timeout the slot is assumed empty.
     */
    private CompletableFuture<Void> runPollPincodes(String sendAddr, Map<String, Object> params) {
        int start = params != null && params.get("start") instanceof Number n ? n.intValue() : 1;
        int end   = params != null && params.get("end")   instanceof Number n ? n.intValue() : 10;
        log.info("Polling pincodes {}-{} for node {}", start, end, sendAddr);

        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int slot = start; slot <= end; slot++) {
            final int s = slot;
            chain = chain.thenCompose(v ->
                getUserCode(sendAddr, s, DEFAULT_EP)
                    .handle((result, ex) -> {
                        if (ex != null) log.debug("Pincode slot {} empty or timed out for {}", s, sendAddr);
                        else            log.debug("Pincode slot {} for {}: {}", s, sendAddr, result);
                        return null;
                    })
            );
        }
        return chain;
    }

    // ── Device state parsing ──────────────────────────────────────────────────

    public Map<String, Object> parseDevice(String id, Device dev) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",               id);
        m.put("protocol",         "zigbee");
        m.put("name",             dev.getName());
        m.put("node",             dev.getIeeeAddr());
        m.put("type",             dev.getType());
        m.put("manufacturer",     dev.getManufacturer());
        m.put("manufacturerId",   dev.getManufacturerId());
        m.put("modelId",          dev.getModelId());
        m.put("descriptor",       dev.getDescriptor());
        m.put("descriptorSource", dev.getDescriptorSource());
        m.put("status",           getAttr(dev, "status", "value"));
        m.put("battery",          getAttr(dev, "battery", "value"));
        return m;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a "raw <clusterId> {01 <seq> <cmdId> <payload>}" CLI command string.
     */
    private String buildRawCmd(String clusterId, String cmdId, String payload) {
        String p = (payload != null && !payload.isBlank()) ? " " + payload : "";
        return "raw " + clusterId + " {01 " + SEQ_NO + " " + cmdId + p + "}";
    }

    /**
     * Send raw command, then send to node, wait for a matching response.
     * Mirrors (cluster-command-wait ...) in interface.clj.
     */
    private ZigbeeMessage clusterCommandWait(
            String rawCmd, String node, int ep,
            Predicate<ZigbeeMessage> matcher) throws Exception {

        // Send the raw frame first (gets a "buffer" ack), then send to the node
        zigbeeInterface.sendCommandWait(rawCmd,
                ZigbeeInterface.typeMatcher("buffer"), TIMEOUT_MS)
                .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

        return zigbeeInterface
                .sendCommandWait("send " + node + " 1 " + ep, matcher, TIMEOUT_MS)
                .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private Object getAttr(Device dev, String cluster, String attr) {
        return dev.getAttributes() != null
                ? Optional.ofNullable(dev.getAttributes().get(cluster))
                           .map(m -> m.get(attr))
                           .orElse(null)
                : null;
    }

    // body helpers
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
}

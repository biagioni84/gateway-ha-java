package uy.plomo.gateway.zwave;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import uy.plomo.zwave.ZWaveProtocol;

import java.net.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Predicate;

// IPv6 address comparison requires canonical form — see normalizeIp()

/**
 * Low-level UDP interface to the Z/IP Gateway (zipgateway).
 *
 * All frames are sent via UnsolicitedListener's persistent socket (port 41231),
 * so the gateway's return path is always that fixed port. This allows both
 * awake-node responses and sleeping-node mailbox responses to arrive at the
 * same listener and be dispatched through the hook registry.
 *
 * Codec separation:
 *   ENCODE (send) → ZWaveProtocol.createZipPacket()  (in ZWaveController)
 *   DECODE (recv) → ZWaveProtocol.parseFrame()        (here)
 *
 * Node addresses: fd00:bbbb::{nodeHex}  port 4123
 *
 * Mirrors zwave/interface.clj:
 *   send-udp-frame  → sendAndReceive
 *   send-udp-wait   → sendAndWait
 *   hook system     → ConcurrentHashMap of CompletableFutures
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ZWaveInterface {

    @Value("${zwave.zipgateway.host.prefix:fd00:bbbb::}")
    private String hostPrefix;

    private final ZWaveProtocol zwaveProtocol;

    // UnsolicitedListener owns the persistent socket; @Lazy breaks the circular dep
    @Lazy private final UnsolicitedListener unsolicitedListener;

    // Hook registry: normalizeIp(nodeIP) → {hookId → PendingHook}
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, PendingHook>> hooks =
            new ConcurrentHashMap<>();

    // ── Address helpers ───────────────────────────────────────────────────────

    public String nodeIp(int nodeId) {
        return hostPrefix + String.format("%02x", nodeId);
    }

    public String nodeIpFromHex(String nodeHex) {
        int nodeId = Integer.parseUnsignedInt(
                nodeHex.replace("0x", "").replace("0X", ""), 16);
        return nodeIp(nodeId);
    }

    // ── Fire-and-forget send ──────────────────────────────────────────────────

    public void send(byte[] frame, String destIp) {
        unsolicitedListener.send(frame, destIp);
    }

    // ── Send + receive ACK (for SET commands) ─────────────────────────────────

    /**
     * Sends a frame and blocks until the gateway returns an ACK, NACK_WAITING,
     * or NACK_QUEUE_FULL for the given sequence number. Returns the parsed packet
     * or null on timeout. The ACK arrives quickly (sub-second) for both awake and
     * sleeping nodes — the 10 s timeout is a generous safety net.
     *
     * Mirrors (send-udp-frame frame ip) in zwave/interface.clj.
     */
    public Map<String, Object> sendAndReceive(byte[] frame, String destIp, int seq) {
        String hookId = UUID.randomUUID().toString();
        String ipKey  = normalizeIp(destIp);
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();

        hooks.computeIfAbsent(ipKey, k -> new ConcurrentHashMap<>())
             .put(hookId, new PendingHook(pkt -> matchesAck(pkt, seq), future));

        send(frame, destIp);
        log.debug("sendAndReceive → {} seq={}", destIp, seq);

        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("sendAndReceive timeout seq={} from {}", seq, destIp);
            return null;
        } catch (Exception e) {
            log.error("sendAndReceive error seq={} dest={}", seq, destIp, e);
            return null;
        } finally {
            removeHook(ipKey, hookId);
        }
    }

    // ── Send + wait for matching response ─────────────────────────────────────

    /**
     * Sends a frame and returns a future that completes with the first response
     * satisfying {@code matcher}. The response may arrive milliseconds later (awake
     * node) or minutes later (sleeping node waking from mailbox) — the caller
     * applies its own timeout via {@code .orTimeout()}.
     *
     * Mirrors (send-udp-wait frame ip matcher timeout) in zwave/interface.clj.
     */
    public CompletableFuture<Map<String, Object>> sendAndWait(
            byte[] frame, String destIp,
            Predicate<Map<String, Object>> matcher, int timeoutMs) {

        return sendAndWait(frame, destIp, matcher)
                .orTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public CompletableFuture<Map<String, Object>> sendAndWait(
            byte[] frame, String destIp,
            Predicate<Map<String, Object>> matcher) {

        String hookId = UUID.randomUUID().toString();
        String ipKey  = normalizeIp(destIp);
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();

        hooks.computeIfAbsent(ipKey, k -> new ConcurrentHashMap<>())
             .put(hookId, new PendingHook(matcher, future));

        send(frame, destIp);
        log.debug("sendAndWait → {} hookId={}", destIp, hookId);

        future.whenComplete((r, ex) -> removeHook(ipKey, hookId));
        return future;
    }

    // ── Hook dispatch (called by UnsolicitedListener) ─────────────────────────

    public void dispatchToHooks(String nodeIpKey, Map<String, Object> packet) {
        dispatchToHooks(normalizeIp(nodeIpKey), packet, null);
    }

    private void dispatchToHooks(String nodeIpKey, Map<String, Object> packet, String exclusiveHookId) {
        Map<String, PendingHook> nodeHooks = hooks.get(nodeIpKey);
        if (nodeHooks == null || packet == null) return;

        nodeHooks.forEach((id, hook) -> {
            if (hook.future().isDone()) { nodeHooks.remove(id); return; }
            if (hook.matcher().test(packet)) {
                hook.future().complete(packet);
                nodeHooks.remove(id);
                log.debug("Hook {} matched packet from {}", id, nodeIpKey);
            }
        });
    }

    private void removeHook(String ipKey, String hookId) {
        Map<String, PendingHook> nodeHooks = hooks.get(ipKey);
        if (nodeHooks != null) nodeHooks.remove(hookId);
    }

    // ── ACK matcher ───────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static boolean matchesAck(Map<String, Object> pkt, int seq) {
        if (pkt == null) return false;
        // parseFrame wraps header inside "data": { class, command, data: { header, ... } }
        Object data = pkt.get("data");
        if (!(data instanceof Map)) return false;
        Map<String, Object> header = getHeader((Map<String, Object>) data);
        if (header == null) return false;
        Object seqnum = header.get("seqnum");
        if (!(seqnum instanceof Number) || ((Number) seqnum).intValue() != seq) return false;
        Map<String, Object> props1 = getProperties1FromHeader(header);
        return props1 != null && (
                Boolean.TRUE.equals(props1.get("ACK_RESPONSE_BIT_MASK_V2"))   ||
                Boolean.TRUE.equals(props1.get("NACK_WAITING_BIT_MASK_V2"))   ||
                Boolean.TRUE.equals(props1.get("NACK_QUEUE_FULL_BIT_MASK_V2"))
        );
    }

    // ── Packet structure helpers ──────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    static Map<String, Object> getHeader(Map<String, Object> parsed) {
        if (parsed == null) return null;
        Object h = parsed.get("header");
        return (h instanceof Map) ? (Map<String, Object>) h : null;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> getProperties1(Map<String, Object> parsed) {
        return getProperties1FromHeader(getHeader(parsed));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getProperties1FromHeader(Map<String, Object> header) {
        if (header == null) return null;
        Object p = header.get("properties1");
        return (p instanceof Map) ? (Map<String, Object>) p : null;
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    static String bytesToHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    /**
     * Produces a canonical key for an IPv6 address so that abbreviated forms
     * (fd00:bbbb::3a) and full forms (fd00:bbbb:0:0:0:0:0:3a) map to the same key.
     * Falls back to upper-casing the raw string if InetAddress parsing fails.
     */
    static String normalizeIp(String ip) {
        try {
            return InetAddress.getByName(ip).getHostAddress().toUpperCase();
        } catch (Exception e) {
            return ip.toUpperCase();
        }
    }

    record PendingHook(Predicate<Map<String, Object>> matcher,
                       CompletableFuture<Map<String, Object>> future) {}
}

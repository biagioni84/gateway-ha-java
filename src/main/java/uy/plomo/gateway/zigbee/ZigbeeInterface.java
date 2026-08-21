package uy.plomo.gateway.zigbee;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Low-level interface to the Z3Gateway CLI subprocess.
 *
 * Mirrors start-z3gateway-cli, send-command, add-hook, send-command-wait
 * from the legacy Clojure implementation.
 *
 * Responsibilities:
 *   - Start/stop the Z3Gateway process
 *   - Write commands to its stdin
 *   - Read and parse output lines
 *   - Dispatch parsed messages to hooks (CompletableFuture matching)
 *   - Dispatch to ZigbeeReportHandler for unsolicited events
 */
@Component
@Slf4j
public class ZigbeeInterface {

    private static final int DEFAULT_TIMEOUT_MS = 30_000;

    // Frame line pattern:
    // T<ts>:RX len <n>, src <node>, ep <ep>, clus <hex> (<name>) FC <fc> seq <seq> cmd <cmd> payload<rest>
    private static final Pattern FRAME_PATTERN = Pattern.compile(
            "T(.*):RX len (.*), src (.*), ep (.*), clus (\\S+) \\((.*)\\) FC (\\S+) seq (\\S+) cmd (\\S+) payload(.*)");

    private static final Pattern BUFFER_PATTERN =
            Pattern.compile(".*buffer:(.*)");
    private static final Pattern ZDO_BIND_PATTERN =
            Pattern.compile(".*ZDO bind req (.*)");
    private static final Pattern TRUST_CENTER_PATTERN =
            Pattern.compile(".*Trust Center Join Handler: status = (.*), decision = (.*), shortid (.*)");
    private static final Pattern IEEE_ADDR_PATTERN =
            Pattern.compile(".*IEEE Address response: \\(>\\)(.*)");
    private static final Pattern NWK_ADDR_PATTERN =
            Pattern.compile(".*NWK Address response: (.*)");
    private static final Pattern IN_CLUSTERS_PATTERN =
            Pattern.compile(".*in_clusters:(.*)");
    private static final Pattern OUT_CLUSTERS_PATTERN =
            Pattern.compile(".*out_clusters:(.*)");
    private static final Pattern INCLUSION_OPENED_PATTERN =
            Pattern.compile(".*EMBER_NETWORK_OPENED: (\\d+)sec");
    private static final Pattern PJOIN_PATTERN =
            Pattern.compile(".*pJoin for (\\d+) sec: 0x00");
    private static final Pattern INCLUSION_CLOSED_PATTERN =
            Pattern.compile(".*EMBER_NETWORK_CLOSED.*");

    // Device table entry: "0 96E4:  0015BC002F009BD9 1 LEVEL_CONTROL_SWITCH JOINED 6"
    // group 1 = NWK short addr (e.g. "96E4"), group 2 = IEEE addr
    private static final Pattern DEVICE_TABLE_PATTERN =
            Pattern.compile("\\d+\\s+([0-9A-Fa-f]{4}):\\s+([0-9A-Fa-f]{16}).*");

    @Value("${zigbee.z3gateway.executable:Z3Gateway}")
    private String executable;

    @Value("${zigbee.z3gateway.args:-n 1 -p /dev/ttyUSB1 -b 115200}")
    private String argsString;

    @Value("${zigbee.enabled:true}")
    private boolean enabled;

    // Hook registry: hookId → PendingHook
    private final ConcurrentHashMap<String, PendingHook> hooks = new ConcurrentHashMap<>();

    private volatile Process   process;
    private volatile PrintWriter stdin;
    private volatile boolean   running;

    // Set by Spring after context init to avoid circular dependency
    private ZigbeeReportHandler reportHandler;

    public void setReportHandler(ZigbeeReportHandler h) {
        this.reportHandler = h;
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @PostConstruct
    public void start() {
        if (!enabled) {
            log.info("Zigbee disabled (zigbee.enabled=false)");
            return;
        }
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(executable);
            cmd.addAll(Arrays.asList(argsString.split("\\s+")));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true); // merge stderr into stdout
            process = pb.start();
            stdin   = new PrintWriter(new BufferedWriter(
                            new OutputStreamWriter(process.getOutputStream())));
            running = true;

            Thread reader = new Thread(this::readLoop, "zigbee-reader");
            reader.setDaemon(true);
            reader.start();

            log.info("Z3Gateway started: {}", cmd);
        } catch (Exception e) {
            log.error("Failed to start Z3Gateway", e);
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (process != null) process.destroy();
        log.info("Z3Gateway stopped");
    }

    // ── Command sending ───────────────────────────────────────────────────────

    /**
     * Fire-and-forget: write a line to the Z3Gateway stdin.
     * Mirrors (send-command cmd) in interface.clj.
     */
    public void sendCommand(String cmd) {
        if (stdin == null) {
            log.warn("sendCommand: Z3Gateway not running — dropping: {}", cmd);
            return;
        }
        log.debug("Z3GW> {}", cmd);
        stdin.println(cmd);
        stdin.flush();
    }

    /**
     * Send a command and return a future that completes with the first message
     * satisfying {@code matcher}.
     * Mirrors (send-command-wait cmd reply) in interface.clj.
     */
    public CompletableFuture<ZigbeeMessage> sendCommandWait(
            String cmd, Predicate<ZigbeeMessage> matcher) {
        return sendCommandWait(cmd, matcher, DEFAULT_TIMEOUT_MS);
    }

    public CompletableFuture<ZigbeeMessage> sendCommandWait(
            String cmd, Predicate<ZigbeeMessage> matcher, int timeoutMs) {

        String hookId = UUID.randomUUID().toString();
        CompletableFuture<ZigbeeMessage> future = new CompletableFuture<>();
        hooks.put(hookId, new PendingHook(matcher, future));

        sendCommand(cmd);

        // timeout
        ScheduledExecutorHolder.SCHEDULER.schedule(() -> {
            if (future.completeExceptionally(
                    new TimeoutException("No matching Zigbee response in " + timeoutMs + "ms"))) {
                hooks.remove(hookId);
                log.warn("sendCommandWait timeout ({}ms) for: {}", timeoutMs, cmd);
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);

        return future.whenComplete((r, ex) -> hooks.remove(hookId));
    }

    /**
     * Sends "plugin device-table print" and collects all device-table-entry messages
     * for a fixed window, then completes with the list of IEEE addresses found.
     * Mirrors requestNodeList() in ZWaveController.
     */
    /**
     * Sends "plugin device-table print", collects all device-table-entry messages
     * for 3 seconds, then returns a deduplicated map of IEEE address → NWK short address.
     * One device may appear multiple times (one per endpoint) — deduplication keeps first.
     */
    public CompletableFuture<Map<String, String>> requestDeviceList() {
        // LinkedHashMap: IEEE → NWK, preserves first-seen NWK per device
        Map<String, String> seen = Collections.synchronizedMap(new LinkedHashMap<>());
        CompletableFuture<Map<String, String>> future = new CompletableFuture<>();

        String hookId = UUID.randomUUID().toString();
        CompletableFuture<ZigbeeMessage> sink = new CompletableFuture<>();
        hooks.put(hookId, new PendingHook(msg -> {
            if ("device-table-entry".equals(msg.getType())
                    && msg.getRawData() != null && msg.getNode() != null) {
                seen.putIfAbsent(msg.getRawData(), msg.getNode()); // IEEE → NWK
            }
            return false;
        }, sink));

        sendCommand("plugin device-table print");

        ScheduledExecutorHolder.SCHEDULER.schedule(() -> {
            hooks.remove(hookId);
            future.complete(seen);
        }, 3, TimeUnit.SECONDS);

        return future;
    }

    // ── Stdout reader loop ────────────────────────────────────────────────────

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                log.debug("Z3GW: {}", line);
                try {
                    ZigbeeMessage msg = parseLine(line);
                    if (msg != null) {
                        dispatchToHooks(msg);
                        if (reportHandler != null) {
                            reportHandler.handleMessage(msg);
                        }
                    }
                } catch (Exception e) {
                    log.error("Error handling Z3GW line '{}'", line, e);
                }
            }
        } catch (IOException e) {
            if (running) log.error("Z3GW read error", e);
        }
        log.info("Z3GW reader loop ended");
    }

    // ── Line parsing ──────────────────────────────────────────────────────────

    /**
     * Parse a single CLI output line into a ZigbeeMessage.
     * Returns null if the line does not match any known pattern.
     * Mirrors (handle-cli-line line) in interface.clj.
     */
    ZigbeeMessage parseLine(String line) {
        Matcher m;

        m = FRAME_PATTERN.matcher(line);
        if (m.matches()) {
            String srcNode   = m.group(3).trim();
            String clusHex   = m.group(5).trim().replace("0x", "").replace("0X", "");
            while (clusHex.length() < 4) clusHex = "0" + clusHex;
            String clusName  = ZigbeeProtocol.clusterName(clusHex);
            String cmdHex    = m.group(9).trim().toLowerCase();
            while (cmdHex.length() < 2) cmdHex = "0" + cmdHex;
            String cmdName   = ZigbeeProtocol.commandName(clusHex, cmdHex);

            // Parse payload bytes from rest
            String payloadRest = m.group(10);
            List<String> payloadBytes = parsePayloadBytes(payloadRest);

            // Decode fields based on cluster+command
            Map<String, String> fields = new LinkedHashMap<>();
            if ("0101".equals(clusHex)) {
                fields = ZigbeeProtocol.decodeDoorLockPayload(cmdName, payloadBytes);
            } else {
                if (!payloadBytes.isEmpty()) fields.put("raw", String.join(" ", payloadBytes));
            }

            return ZigbeeMessage.builder("frame")
                    .node(srcNode)
                    .srcEp(m.group(4).trim())
                    // dstEp is not present in the RX frame log line — left null
                    .clusterHex(clusHex)
                    .cluster(clusName)
                    .commandHex(cmdHex)
                    .command(cmdName)
                    .rawPayload(payloadBytes)
                    .fields(fields)
                    .build();
        }

        m = BUFFER_PATTERN.matcher(line);
        if (m.matches()) {
            return ZigbeeMessage.builder("buffer").rawData(m.group(1).trim()).build();
        }

        m = ZDO_BIND_PATTERN.matcher(line);
        if (m.matches()) {
            return ZigbeeMessage.builder("zdo-bind-req").rawData(m.group(1).trim()).build();
        }

        m = TRUST_CENTER_PATTERN.matcher(line);
        if (m.matches()) {
            String status = m.group(1).trim();
            String node   = m.group(3).trim();
            return ZigbeeMessage.builder("trust_center")
                    .node(node)
                    .fields(Map.of("status", status, "event", m.group(2).trim()))
                    .build();
        }

        m = IEEE_ADDR_PATTERN.matcher(line);
        if (m.matches()) {
            return ZigbeeMessage.builder("ieee-address").rawData(m.group(1).trim()).build();
        }

        m = NWK_ADDR_PATTERN.matcher(line);
        if (m.matches()) {
            return ZigbeeMessage.builder("nwk-address").rawData(m.group(1).trim()).build();
        }

        m = IN_CLUSTERS_PATTERN.matcher(line);
        if (m.matches()) {
            return ZigbeeMessage.builder("in-clusters").rawData(m.group(1).trim()).build();
        }

        m = INCLUSION_OPENED_PATTERN.matcher(line);
        if (m.matches()) {
            return ZigbeeMessage.builder("inclusion_started").rawData(m.group(1)).build();
        }

        m = PJOIN_PATTERN.matcher(line);
        if (m.matches()) {
            int secs = Integer.parseInt(m.group(1));
            return ZigbeeMessage.builder(secs > 0 ? "inclusion_started" : "inclusion_finished")
                    .rawData(m.group(1)).build();
        }

        m = INCLUSION_CLOSED_PATTERN.matcher(line);
        if (m.matches()) {
            return ZigbeeMessage.builder("inclusion_finished").build();
        }

        m = DEVICE_TABLE_PATTERN.matcher(line);
        if (m.matches()) {
            // node = "0x<NWK>", rawData = IEEE addr
            return ZigbeeMessage.builder("device-table-entry")
                    .node("0x" + m.group(1).toUpperCase())
                    .rawData(m.group(2).trim())
                    .build();
        }

        return null; // unrecognized line
    }

    /** Extract payload bytes from the tail of a frame line, e.g. "[01 02 FF ]" → ["01","02","FF"]. */
    private List<String> parsePayloadBytes(String rest) {
        if (rest == null) return List.of();
        Matcher m = Pattern.compile("\\[(.*)\\]").matcher(rest);
        if (!m.find()) return List.of();
        String inner = m.group(1).trim();
        if (inner.isEmpty()) return List.of();
        return Arrays.asList(inner.split("\\s+"));
    }

    // ── Hook dispatch ─────────────────────────────────────────────────────────

    private void dispatchToHooks(ZigbeeMessage msg) {
        hooks.forEach((id, hook) -> {
            if (hook.future().isDone()) { hooks.remove(id); return; }
            if (hook.matcher().test(msg)) {
                hook.future().complete(msg);
                hooks.remove(id);
                log.debug("Hook {} matched: {}", id, msg);
            }
        });
    }

    // ── Matcher builders ──────────────────────────────────────────────────────

    /**
     * Builds a matcher that checks type, node (optional), cluster name, command name.
     * Also checks specific field values if provided (fieldMatches map).
     */
    public static Predicate<ZigbeeMessage> frameMatcher(
            String node, String cluster, String command, Map<String, String> fieldMatches) {
        return msg -> {
            if (!"frame".equals(msg.getType())) return false;
            if (node    != null && !node.equalsIgnoreCase(msg.getNode()))    return false;
            if (cluster != null && !cluster.equals(msg.getCluster()))        return false;
            if (command != null && !command.equals(msg.getCommand()))        return false;
            if (fieldMatches != null) {
                for (Map.Entry<String, String> e : fieldMatches.entrySet()) {
                    String actual = msg.field(e.getKey());
                    if (!e.getValue().equals(actual)) return false;
                }
            }
            return true;
        };
    }

    public static Predicate<ZigbeeMessage> typeMatcher(String type) {
        return msg -> type.equals(msg.getType());
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    record PendingHook(Predicate<ZigbeeMessage> matcher, CompletableFuture<ZigbeeMessage> future) {}

    /** Lazy singleton scheduler for timeouts. */
    private static class ScheduledExecutorHolder {
        static final ScheduledExecutorService SCHEDULER =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "zigbee-timeout");
                    t.setDaemon(true);
                    return t;
                });
    }
}

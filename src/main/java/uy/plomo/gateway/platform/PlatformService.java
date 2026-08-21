package uy.plomo.gateway.platform;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Low-level platform operations: serial number, timezone, SSH public key,
 * SSH reverse tunnels, and AWS Secure Tunneling via localproxy.
 * All shell commands use ProcessBuilder (no Runtime.exec / no bash -c).
 * Mirrors legacy Clojure implementation.controller functions.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PlatformService {

    public String getSerialNumber() {
        try {
            return java.nio.file.Files.readAllLines(java.nio.file.Path.of("/proc/cpuinfo")).stream()
                    .filter(line -> line.startsWith("Serial"))
                    .findFirst()
                    .map(line -> { String[] p = line.split(":\\s*", 2); return p.length > 1 ? p[1].trim() : line.trim(); })
                    .orElse("unknown");
        } catch (Exception e) {
            log.warn("getSerialNumber failed", e);
        }
        return "unknown";
    }

    public String getTimezone() {
        try {
            String tz = java.nio.file.Files.readString(java.nio.file.Path.of("/etc/timezone")).trim();
            if (!tz.isBlank()) return tz;
        } catch (Exception e) {
            log.warn("getTimezone failed", e);
        }
        return "UTC";
    }

    private static final java.util.regex.Pattern TZ_PATTERN =
            java.util.regex.Pattern.compile("[A-Za-z0-9/_+\\-]+");

    public boolean setTimezone(String timezone) {
        if (timezone == null || timezone.isBlank()) return false;
        if (!TZ_PATTERN.matcher(timezone).matches()) {
            log.warn("setTimezone: rejected invalid timezone value '{}'", timezone);
            return false;
        }
        // Pass args directly — no shell interpolation, no injection risk.
        return exec(10, "sudo", "timedatectl", "set-timezone", timezone).exitCode() == 0;
    }

    /**
     * Returns the base64 key-body portion of the RSA public key
     * (ssh-keygen -y -f ~/.ssh/id_rsa).
     * Mirrors get-public-key in controller.clj.
     */
    public String getPublicKey() {
        String keyPath = System.getProperty("user.home") + "/.ssh/id_rsa";
        String out = exec(10, "ssh-keygen", "-y", "-f", keyPath).output().trim();
        if (!out.isBlank()) {
            String[] parts = out.split("\\s+");
            return parts.length > 1 ? parts[1] : out;
        }
        return null;
    }

    // ── SSH reverse tunnels ───────────────────────────────────────────────────

    /** Validates hostname / IPv4 — rejects path traversal or shell metacharacters. */
    private static final Pattern ADDR_PATTERN   = Pattern.compile("[A-Za-z0-9.\\-]+");
    /** Matches the -R forward spec inside ps output: dstPort:srcAddr:srcPort */
    private static final Pattern TUNNEL_SPEC    = Pattern.compile("-R\\s+(\\d+):([\\w.\\-]+):(\\d+)");
    /** Matches the target user@host in ps output: tunneluser@{dstAddr} */
    private static final Pattern TUNNEL_TARGET  = Pattern.compile("tunneluser@([\\w.\\-]+)");
    /** Matches a numeric PID at the start of a ps line. */
    private static final Pattern PID_PATTERN    = Pattern.compile("^\\s*(\\d+)");

    /**
     * Starts an SSH reverse tunnel:
     *   ssh -fNT -R {dstPort}:{srcAddr}:{srcPort} -i /tmp/priv.pem iot_{serial}@{dstAddr}
     *
     * ssh -fNT forks into background immediately so waitFor() returns right away.
     * Mirrors legacy Clojure implementation.
     */
    public Map<String, Object> createReverseTunnel(
            String srcAddr, int srcPort, String dstAddr, int dstPort) {
        if (srcAddr == null || !ADDR_PATTERN.matcher(srcAddr).matches())
            return Map.of("error", "invalid src-addr");
        if (dstAddr == null || !ADDR_PATTERN.matcher(dstAddr).matches())
            return Map.of("error", "invalid dst-addr");
        if (srcPort < 1 || srcPort > 65535 || dstPort < 1 || dstPort > 65535)
            return Map.of("error", "invalid port");

        String remoteForward = "0.0.0.0:" + dstPort + ":" + srcAddr + ":" + srcPort;
        String target        = "tunneluser@" + dstAddr;

        try {
            Process p = new ProcessBuilder(
                    "ssh", "-NT",
                    "-o", "StrictHostKeyChecking=no",
                    "-o", "BatchMode=yes",
                    "-o", "ServerAliveInterval=30",
                    "-R", remoteForward,
                    "-i", "/tmp/priv.pem",
                    target)
                    .redirectErrorStream(true).start();

            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    br.lines().forEach(line -> log.info("ssh tunnel [{}]: {}", remoteForward, line));
                } catch (Exception ignored) {}
                int code;
                try { code = p.waitFor(); } catch (InterruptedException e) { return; }
                if (code != 0) log.warn("ssh tunnel [{}] exited with code {}", remoteForward, code);
                else           log.info("ssh tunnel [{}] closed", remoteForward);
            }, "ssh-tunnel-" + dstPort);
            reader.setDaemon(true);
            reader.start();

            return Map.of("status", "ok",
                    "src_addr", srcAddr, "src_port", srcPort,
                    "dst_addr", dstAddr, "dst_port", dstPort);
        } catch (Exception e) {
            log.error("createReverseTunnel failed", e);
            return Map.of("error", e.getMessage());
        }
    }

    /**
     * Lists SSH reverse tunnels started for this gateway serial number.
     * Uses ps to find ssh processes matching iot_{serial}.
     * Mirrors legacy Clojure implementation.
     */
    public List<Map<String, Object>> listRunningTunnels() {
        try {
            String out = exec(10, "ps", "-ax", "-o", "pid,cmd").output();

            List<Map<String, Object>> tunnels = new ArrayList<>();
            for (String line : out.split("\n")) {
                if (!line.contains("tunneluser@")) continue;

                Matcher pidM = PID_PATTERN.matcher(line);
                String  pid  = pidM.find() ? pidM.group(1) : "";

                Matcher specM = TUNNEL_SPEC.matcher(line);
                if (specM.find()) {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("pid",      pid);
                    t.put("dst_port", Integer.parseInt(specM.group(1)));
                    t.put("src_addr", specM.group(2));
                    t.put("src_port", Integer.parseInt(specM.group(3)));
                    Matcher tgtM = TUNNEL_TARGET.matcher(line);
                    if (tgtM.find()) t.put("dst_addr", tgtM.group(1));
                    tunnels.add(t);
                } else {
                    tunnels.add(Map.of("pid", pid, "cmd", line.trim()));
                }
            }
            return tunnels;
        } catch (Exception e) {
            log.warn("listRunningTunnels failed", e);
            return List.of();
        }
    }

    /**
     * Kills all SSH reverse tunnel processes for this gateway serial.
     * Mirrors legacy Clojure implementation.
     */
    public Map<String, Object> stopRunningTunnels() {
        List<Map<String, Object>> tunnels = listRunningTunnels();
        int killed = 0;
        for (Map<String, Object> t : tunnels) {
            Object pidObj = t.get("pid");
            if (pidObj == null) continue;
            String pid = pidObj.toString();
            if (!pid.matches("\\d+")) continue;  // never pass non-numeric to kill
            if (exec(5, "kill", "-9", pid).exitCode() == 0) killed++;
            else log.warn("Failed to kill pid {}", pid);
        }
        return Map.of("status", "ok", "killed", killed);
    }

    /**
     * Kills the SSH reverse tunnel matching the given src/dst parameters.
     * Mirrors legacy Clojure implementation.
     */
    public Map<String, Object> stopRunningTunnel(String srcAddr, int srcPort, String dstAddr, int dstPort) {
        List<Map<String, Object>> tunnels = listRunningTunnels();
        int killed = 0;
        for (Map<String, Object> t : tunnels) {
            if (!String.valueOf(dstPort).equals(String.valueOf(t.get("dst_port")))) continue;
            if (!String.valueOf(srcPort).equals(String.valueOf(t.get("src_port")))) continue;
            if (srcAddr != null && !srcAddr.equals(t.get("src_addr"))) continue;
            if (dstAddr != null && !dstAddr.equals(t.get("dst_addr"))) continue;
            Object pidObj = t.get("pid");
            if (pidObj == null) continue;
            String pid = pidObj.toString();
            if (!pid.matches("\\d+")) continue;
            if (exec(5, "kill", "-9", pid).exitCode() == 0) killed++;
            else log.warn("Failed to kill pid {}", pid);
        }
        return Map.of("status", "ok", "killed", killed);
    }

    // ── Process helper ───────────────────────────────────────────────────────

    private record ExecResult(int exitCode, String output) {}

    /**
     * Runs a process, reads its stdout off-thread, and enforces a hard timeout.
     * Destroys the process forcibly if it doesn't finish in time.
     */
    private ExecResult exec(int timeoutSecs, String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            // Read stdout on a separate thread so it doesn't block if the process hangs.
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    return br.lines().collect(Collectors.joining("\n"));
                } catch (Exception e) {
                    return "";
                }
            });
            if (!p.waitFor(timeoutSecs, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("Process timed out after {}s: {}", timeoutSecs, Arrays.toString(cmd));
                return new ExecResult(-1, "");
            }
            String output = outputFuture.get(2, TimeUnit.SECONDS);
            return new ExecResult(p.exitValue(), output);
        } catch (Exception e) {
            log.warn("exec failed {}: {}", Arrays.toString(cmd), e.getMessage());
            return new ExecResult(-1, "");
        }
    }

    // ── AWS Secure Tunneling ──────────────────────────────────────────────────

    /**
     * Starts the AWS IoT `localproxy` process for the destination side of a
     * Secure Tunnel (IoT → device direction).
     *
     * AWS Secure Tunneling notification payload:
     *   { "clientAccessToken": "...", "clientMode": "destination",
     *     "region": "us-east-1", "services": ["SSH"] }
     *
     * The localproxy listens on {@code localPort} and forwards traffic to the
     * service (usually SSH on port 22).  The cloud-side source connects through
     * the AWS Secure Tunneling service to reach it.
     *
     * @param region            AWS region from the notification payload
     * @param clientAccessToken token from the notification payload
     * @param localPort         local port of the service (e.g. 22 for SSH)
     */
    public void startSecureTunnel(String region, String clientAccessToken, int localPort) {
        log.info("Starting AWS Secure Tunnel — region={} localPort={}", region, localPort);
        try {
            // localproxy must be installed on the device, e.g. /usr/local/bin/localproxy
            Process p = new ProcessBuilder(
                    "localproxy",
                    "-r", region,
                    "-d", String.valueOf(localPort),
                    "-t", clientAccessToken)
                    .redirectErrorStream(true)
                    .start();
            // Log output in a daemon thread — process runs until tunnel is closed.
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(p.getInputStream()))) {
                    br.lines().forEach(line -> log.debug("localproxy: {}", line));
                } catch (Exception ignored) {}
            }, "localproxy-reader");
            reader.setDaemon(true);
            reader.start();
        } catch (Exception e) {
            log.error("Failed to start localproxy", e);
        }
    }

}

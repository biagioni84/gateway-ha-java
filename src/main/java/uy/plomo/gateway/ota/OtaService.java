package uy.plomo.gateway.ota;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * OTA (Over-The-Air) update for the gateway JAR.
 *
 * Flow:
 *   1. Validate URL (https only) and checksum (SHA-256 hex).
 *   2. Download to a temp file.
 *   3. Verify SHA-256 — abort if mismatch.
 *   4. Atomic move to the configured jar-path.
 *   5. Return immediately, then async-restart via systemctl.
 *
 * TODO (S-OTA-1): Verify an RSA/ECDSA signature on the downloaded JAR
 *   before installing. The public key should be embedded in the running
 *   binary or stored in a read-only location on the filesystem.
 *
 * TODO (S-OTA-2): Implement rollback — copy current JAR to gateway.jar.bak
 *   before replacing it. A wrapper start script should detect repeated
 *   crash-loop failures and restore the backup automatically.
 *
 * Disabled entirely in container mode (gateway.deployment.mode=container, set by the Dockerfile):
 * `sudo systemctl restart` has no systemd to talk to in a container, and rewriting the JAR inside
 * an image doesn't survive a restart anyway. Updates there come from the HA add-on store or
 * `docker pull` + recreate instead — this is a deliberate gap (see known-gaps memory / TODO.md),
 * not a bug to silently work around.
 */
@Service
@Slf4j
public class OtaService {

    @Value("${gateway.ota.jar-path:/opt/gateway/gateway.jar}")
    private String jarPath;

    @Value("${gateway.ota.service-name:gateway}")
    private String serviceName;

    @Value("${gateway.deployment.mode:bare-metal}")
    private String deploymentMode;

    public Map<String, Object> update(String url, String checksum) {
        if ("container".equalsIgnoreCase(deploymentMode)) {
            return Map.of("status", "not_supported",
                    "message", "OTA via MQTT isn't supported in container mode — update via the " +
                            "HA add-on store or `docker pull` + recreate instead");
        }
        if (url == null || !url.startsWith("https://"))
            return Map.of("error", "url must use https://");
        if (checksum == null || checksum.isBlank())
            return Map.of("error", "checksum is required (SHA-256 hex)");

        Path tmp = null;
        try {
            tmp = Files.createTempFile("gateway-update-", ".jar");

            // ── Download ──────────────────────────────────────────────────────
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMinutes(5))
                    .GET()
                    .build();
            HttpResponse<Path> resp = http.send(req, HttpResponse.BodyHandlers.ofFile(tmp));
            if (resp.statusCode() != 200) {
                return Map.of("error", "download failed: HTTP " + resp.statusCode());
            }
            log.info("OTA: downloaded {} bytes from {}", Files.size(tmp), url);

            // ── Verify checksum ───────────────────────────────────────────────
            String actual   = sha256(tmp);
            String expected = checksum.toLowerCase().replace("sha256:", "").strip();
            if (!actual.equals(expected)) {
                log.error("OTA: checksum mismatch — expected={} actual={}", expected, actual);
                return Map.of("error", "checksum mismatch");
            }
            log.info("OTA: checksum verified ({})", actual);

            // ── Install ───────────────────────────────────────────────────────
            Path dest = Path.of(jarPath);
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("OTA: installed new JAR to {}", dest);
            tmp = null; // moved — don't delete in finally

            // ── Restart (async — response must go out before process dies) ────
            CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(500);
                    exec(10, "sudo", "systemctl", "restart", serviceName);
                } catch (Exception e) {
                    log.error("OTA: restart failed — manual restart required", e);
                }
            });

            return Map.of("status", "ok", "message", "update installed — restarting");

        } catch (Exception e) {
            log.error("OTA: update failed", e);
            return Map.of("error", "update failed: " + e.getMessage());
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
        }
    }

    private String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream is = new BufferedInputStream(Files.newInputStream(file))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private void exec(int timeoutSecs, String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        if (!p.waitFor(timeoutSecs, TimeUnit.SECONDS)) p.destroyForcibly();
    }
}

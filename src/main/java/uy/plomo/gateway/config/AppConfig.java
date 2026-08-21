package uy.plomo.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import uy.plomo.zwave.ZWaveProtocol;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads gateway.conf and provisioned.creds at startup.
 *
 * gateway.conf: ignored for now — all config lives in application.properties.
 * provisioned.creds: parsed from EDN (legacy Clojure) or JSON (new Java format).
 */
@Component
@Slf4j
public class AppConfig {

    @Value("${gateway.creds.path:./provisioned.creds}")
    private String credsPath;

    @Getter
    private ProvisionedCreds creds = new ProvisionedCreds();

    @Autowired
    private ObjectMapper objectMapper;

    @PostConstruct
    public void load() {
        loadCreds();
        writePrivKey();
    }

    private void loadCreds() {
        File file = new File(credsPath);
        if (!file.exists()) {
            log.warn("provisioned.creds not found at '{}' — gateway is not provisioned", credsPath);
            return;
        }
        try {
            String content = Files.readString(Path.of(credsPath));
            if (content.trim().startsWith("{\"")) {
                creds = objectMapper.readValue(content, ProvisionedCreds.class);
                log.info("Loaded provisioned.creds (JSON) — name={}", creds.getName());
            } else {
                creds = parseEdn(content);
                log.info("Loaded provisioned.creds (EDN) — name={}", creds.getName());
            }
        } catch (IOException e) {
            log.error("Failed to load provisioned.creds", e);
        }
    }

    @Bean
    public ZWaveProtocol zwaveProtocol() {
        return new ZWaveProtocol();
    }

    public void saveCreds() {
        try {
            Path path = Path.of(credsPath);
            // Ensure the file exists with owner-only permissions before writing credentials.
            if (!Files.exists(path)) {
                try {
                    Files.createFile(path,
                            java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
                } catch (UnsupportedOperationException ignored) {} // non-POSIX
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), creds);
            try {
                Files.setPosixFilePermissions(path,
                        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {}
            log.info("Saved provisioned.creds — name={}", creds.getName());
        } catch (IOException e) {
            log.error("Failed to save provisioned.creds", e);
        }
    }

    private void writePrivKey() {
        String key = creds.getPrivateKey();
        if (key == null || key.isBlank()) {
            log.warn("provisioned.creds has no private-key — /tmp/priv.pem will not be written");
            return;
        }
        try {
            Path pem = Path.of("/tmp/priv.pem");
            // Create with owner-read-only from the start to avoid a TOCTOU window
            // where the file exists world-readable before chmod can run.
            try {
                Files.createFile(pem,
                        java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // File exists from a prior run — overwrite it; permissions are already restricted.
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX OS — fall back to plain create; setPosixFilePermissions below is also skipped.
            }
            Files.writeString(pem, key);
            try {
                Files.setPosixFilePermissions(pem, Set.of(PosixFilePermission.OWNER_READ));
            } catch (UnsupportedOperationException ignored) {}
            log.info("Written /tmp/priv.pem from provisioned.creds");
        } catch (IOException e) {
            log.error("Failed to write /tmp/priv.pem", e);
        }
    }

    /**
     * Minimal EDN parser for the provisioned.creds format written by the Clojure gateway.
     * Handles: {:name "..." :cert-pem "..." :private-key "..." :cert-id "..." :serial-number "..."}
     * Does NOT handle nested maps or complex EDN.
     */
    private ProvisionedCreds parseEdn(String edn) {
        ProvisionedCreds c = new ProvisionedCreds();
        c.setName(ednString(edn, "name"));
        c.setCertPem(ednString(edn, "cert-pem"));
        c.setPrivateKey(ednString(edn, "private-key"));
        c.setCertId(ednString(edn, "cert-id"));
        c.setSerialNumber(ednString(edn, "serial-number"));
        return c;
    }

    private String ednString(String edn, String key) {
        Matcher m = Pattern.compile(":" + Pattern.quote(key) + "\\s+\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(edn);
        if (m.find()) {
            return m.group(1).replace("\\n", "\n").replace("\\\"", "\"");
        }
        return null;
    }
}

package uy.plomo.gateway.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uy.plomo.gateway.config.AppConfig;
import uy.plomo.gateway.config.ProvisionedCreds;

import java.util.Map;
import java.util.Objects;

/**
 * Manual provisioning REST API — lets an operator load AWS IoT credentials by hand
 * (via the /setup.html page) instead of relying solely on the external fleet-provisioning
 * flow that writes provisioned.creds directly.
 *
 * Routes:
 *   GET  /api/v1/provisioning — current status (never returns certPem/privateKey)
 *   POST /api/v1/provisioning — save new credentials (requires a gateway restart to take effect)
 */
@RestController
@RequestMapping("/api/v1/provisioning")
@RequiredArgsConstructor
@Slf4j
public class ProvisioningController {

    private final AppConfig appConfig;

    @GetMapping
    public Map<String, Object> status() {
        ProvisionedCreds creds = appConfig.getCreds();
        return Map.of(
                "provisioned", creds.isComplete(),
                "name", Objects.toString(creds.getName(), ""),
                "iotEndpoint", Objects.toString(creds.getIotEndpoint(), ""),
                "certId", Objects.toString(creds.getCertId(), ""),
                "serialNumber", Objects.toString(creds.getSerialNumber(), "")
        );
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> provision(@RequestBody ProvisionedCreds body) {
        if (isBlank(body.getName()) || isBlank(body.getCertPem())
                || isBlank(body.getPrivateKey()) || isBlank(body.getIotEndpoint())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "name, certPem, privateKey and iotEndpoint are required"));
        }

        appConfig.updateCreds(body);
        log.info("Provisioning: credentials updated via manual provisioning UI (name={})", body.getName());

        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "message", "Credentials saved — restart the gateway to apply them"));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

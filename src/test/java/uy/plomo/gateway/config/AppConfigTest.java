package uy.plomo.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for a real bug hit provisioning a device via /setup.html: saveCreds() writes
 * pretty-printed JSON ("{\n  \"name\" : ..."), which loadCreds()'s old string-prefix sniff
 * ("{\"".equals) failed to recognize as JSON, silently falling back to the (now-removed) EDN
 * parser and losing every field (logged as "Loaded provisioned.creds (EDN) — name=null").
 */
class AppConfigTest {

    @Test
    void saveThenLoad_roundTripsThroughPrettyPrintedJson(@org.junit.jupiter.api.io.TempDir Path tempDir) {
        AppConfig config = new AppConfig();
        ReflectionTestUtils.setField(config, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(config, "credsPath", tempDir.resolve("provisioned.creds").toString());

        ProvisionedCreds creds = new ProvisionedCreds();
        creds.setName("gw-test");
        creds.setCertPem("-----BEGIN CERTIFICATE-----\nabc\n-----END CERTIFICATE-----");
        creds.setPrivateKey("-----BEGIN RSA PRIVATE KEY-----\ndef\n-----END RSA PRIVATE KEY-----");
        creds.setIotEndpoint("test.iot.us-east-1.amazonaws.com");

        config.updateCreds(creds);

        AppConfig reloaded = new AppConfig();
        ReflectionTestUtils.setField(reloaded, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(reloaded, "credsPath", tempDir.resolve("provisioned.creds").toString());
        reloaded.load();

        assertThat(reloaded.getCreds().getName()).isEqualTo("gw-test");
        assertThat(reloaded.getCreds().getIotEndpoint()).isEqualTo("test.iot.us-east-1.amazonaws.com");
        assertThat(reloaded.getCreds().isComplete()).isTrue();
    }
}

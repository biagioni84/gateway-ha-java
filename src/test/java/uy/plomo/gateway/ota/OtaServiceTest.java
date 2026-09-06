package uy.plomo.gateway.ota;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OTA has no REST route (it's MQTT-only, see GatewayApiService.dispatch()), so the container-mode
 * short-circuit is exercised directly here rather than over HTTP.
 */
class OtaServiceTest {

    @Test
    void update_returnsNotSupported_inContainerMode() {
        OtaService service = new OtaService();
        ReflectionTestUtils.setField(service, "deploymentMode", "container");

        Map<String, Object> result = service.update("https://example.com/gateway.jar", "deadbeef");

        assertThat(result).containsEntry("status", "not_supported");
    }

    @Test
    void update_rejectsNonHttpsUrl_inBareMetalMode() {
        OtaService service = new OtaService();
        ReflectionTestUtils.setField(service, "deploymentMode", "bare-metal");

        Map<String, Object> result = service.update("http://example.com/gateway.jar", "deadbeef");

        assertThat(result).containsKey("error");
    }
}

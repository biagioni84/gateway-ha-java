package uy.plomo.gateway.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import uy.plomo.gateway.config.AppConfig;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * getSerialNumber()'s /proc/cpuinfo tier isn't exercised directly here (environment-dependent —
 * present on real Pi/Broadcom hardware, absent everywhere else including this dev machine), but
 * that's exactly the point of the other two tiers: they're what actually resolve gw_id on any
 * host that isn't a Pi.
 */
class PlatformServiceTest {

    @Test
    void getSerialNumber_prefersProvisionedSerialOverEverythingElse() {
        AppConfig appConfig = new AppConfig();
        appConfig.getCreds().setSerialNumber("PROVISIONED-123");
        PlatformService service = new PlatformService(appConfig);

        assertThat(service.getSerialNumber()).isEqualTo("PROVISIONED-123");
    }

    @Test
    void getSerialNumber_generatesAndPersistsFallback_whenNothingElseAvailable(@TempDir Path tempDir) {
        AppConfig appConfig = new AppConfig(); // no provisioned serialNumber
        PlatformService service = new PlatformService(appConfig);
        ReflectionTestUtils.setField(service, "serialPath", tempDir.resolve("gateway.serial").toString());

        String first = service.getSerialNumber();
        assertThat(first).isNotBlank();

        String second = service.getSerialNumber();
        assertThat(second).isEqualTo(first); // stable across calls — read back from the persisted file
    }
}

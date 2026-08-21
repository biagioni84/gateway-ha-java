package uy.plomo.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.autoconfigure.web.DataWebAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke test — verifies that the Spring context starts cleanly.
 *
 * SpringDataWebAutoConfiguration is excluded because it initialises
 * ProjectingJacksonHttpMessageConverter, which has a Jackson 3.x compat
 * issue (NoSuchFieldError: POJO in jackson-annotations:2.20) that only
 * surfaces in the test JVM on this platform. The gateway does not use
 * Spring Data projections or Pageable binding, so the exclusion is safe.
 */
@SpringBootTest
@ActiveProfiles("test")
@ImportAutoConfiguration(exclude = DataWebAutoConfiguration.class)
class GatewayApplicationTests {

    @Test
    void contextLoads() {
    }
}

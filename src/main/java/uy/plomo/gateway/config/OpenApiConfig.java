package uy.plomo.gateway.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.PathParameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME_NAME = "apiKeyAuth";

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Gateway API")
                        .version("0.1")
                        .description("Local REST API for the IoT gateway. " +
                                "Authentication via X-Api-Key header (when gateway.api.key is configured). " +
                                "All remote access goes through MQTT — HTTP is localhost-only by default."))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(new Components()
                        .addParameters("devParam", new PathParameter()
                                .name("dev")
                                .description("Device UUID")
                                .required(true)
                                .example("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
                                .schema(new StringSchema()))
                        .addSecuritySchemes(SECURITY_SCHEME_NAME, new SecurityScheme()
                                .name("X-Api-Key")
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .description("API key set via gateway.api.key property. " +
                                        "Leave blank when running locally (server.address=127.0.0.1).")));
    }
}

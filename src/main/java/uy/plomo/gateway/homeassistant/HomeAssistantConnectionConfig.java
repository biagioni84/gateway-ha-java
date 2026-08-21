package uy.plomo.gateway.homeassistant;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves how to reach Home Assistant's WebSocket/REST API.
 *
 * Two modes:
 *   - Supervisor-managed addon: SUPERVISOR_TOKEN env var is present -> use Supervisor's
 *     internal proxy (http://supervisor/core, ws://supervisor/core/websocket) and that token.
 *   - Standalone (local dev, or bare-metal deployment): homeassistant.url + homeassistant.token
 *     properties, mirroring the project's existing matter.server.url-style config pattern.
 */
@Component
@Slf4j
public class HomeAssistantConnectionConfig {

    @Value("${homeassistant.enabled:true}")
    private boolean enabled;

    @Value("${homeassistant.url:http://localhost:8123}")
    private String standaloneUrl;

    @Value("${homeassistant.token:}")
    private String standaloneToken;

    @Value("${homeassistant.ws.path:/api/websocket}")
    private String wsPath;

    private String httpBaseUrl;
    private String wsUrl;
    private String token;
    private boolean supervisorMode;

    @PostConstruct
    void resolve() {
        String supervisorToken = System.getenv("SUPERVISOR_TOKEN");
        if (supervisorToken != null && !supervisorToken.isBlank()) {
            supervisorMode = true;
            httpBaseUrl = "http://supervisor/core";
            wsUrl = "ws://supervisor/core/websocket";
            token = supervisorToken;
            log.info("Home Assistant: Supervisor-managed mode detected");
        } else {
            supervisorMode = false;
            httpBaseUrl = standaloneUrl;
            wsUrl = toWsUrl(standaloneUrl) + wsPath;
            token = standaloneToken;
            log.info("Home Assistant: standalone mode — connecting to {}", httpBaseUrl);
        }
    }

    private static String toWsUrl(String httpUrl) {
        if (httpUrl.startsWith("https://")) return "wss://" + httpUrl.substring("https://".length());
        if (httpUrl.startsWith("http://"))  return "ws://"  + httpUrl.substring("http://".length());
        return httpUrl;
    }

    public boolean isEnabled()        { return enabled; }
    public boolean isSupervisorMode() { return supervisorMode; }
    public String  getHttpBaseUrl()   { return httpBaseUrl; }
    public String  getWsUrl()         { return wsUrl; }
    public String  getToken()         { return token; }
}

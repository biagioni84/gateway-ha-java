package uy.plomo.gateway.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uy.plomo.gateway.api.GatewayApiService;

import java.util.Map;

/**
 * Routes incoming MQTT commands to GatewayApiService.
 *
 * The MQTT message format (from cloud-side) is:
 *   { "path": "GET:/summary", "command": "{...json body...}" }
 *
 * MqttService parses the envelope and calls dispatch(method, path, body).
 * This class resolves the call to the appropriate service method.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MqttDispatcher {

    private final GatewayApiService apiService;
    private final ObjectMapper objectMapper;

    /**
     * Dispatch an incoming MQTT command to the correct service.
     *
     * @param method HTTP-style method: "GET" | "POST" | "DELETE"
     * @param path   API path, e.g. "/summary", "/include", "/{devId}/pincode/3"
     * @param body   JSON body string (may be null or "{}" for GET)
     * @return JSON response string to be published back to the response topic
     */
    public String dispatch(String method, String path, String body) {
        log.debug("MQTT dispatch: {} {} body={}", method, path, body);
        try {
            Map<String, Object> result = apiService.dispatch(method, path, body);
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("dispatch error for {} {}", method, path, e);
            return "{\"error\":\"internal error\"}";
        }
    }
}

package uy.plomo.gateway.homeassistant.camera;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uy.plomo.gateway.device.Device;
import uy.plomo.gateway.homeassistant.HAState;
import uy.plomo.gateway.homeassistant.HomeAssistantConnectionConfig;
import uy.plomo.gateway.homeassistant.HomeAssistantInterface;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Camera operations for HA-managed cameras (Device.protocol="ha", node="camera.xxx").
 * Mirrors CameraController's role for the old go2rtc-backed cameras, which stays in place
 * unchanged for existing protocol="camera" devices.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class HomeAssistantCameraController {

    private final HomeAssistantInterface        haInterface;
    private final HomeAssistantConnectionConfig  connectionConfig;

    public Map<String, Object> parseDevice(String id, Device dev) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id",       id);
        out.put("protocol", "ha");
        out.put("name",     dev.getName());
        out.put("node",     dev.getNode());
        out.put("type",     "camera");

        String entityId = dev.getNode();
        HAState cached = entityId != null ? haInterface.getState(entityId) : null;
        boolean available = cached != null
                && !"unavailable".equals(cached.state()) && !"unknown".equals(cached.state());

        out.put("manufacturer",   dev.getManufacturer());
        out.put("manufacturerId", dev.getManufacturerId());
        out.put("modelId",        dev.getModelId());
        out.put("available",      available);
        out.put("status",         available ? "streaming" : "offline");
        out.put("battery",        null);
        return out;
    }

    public Map<String, Object> handleDeviceCommand(Device dev, String cmd, String method, Map<String, Object> body) {
        String entityId = dev.getNode();
        if (entityId == null) return Map.of("error", "device has no Home Assistant entity id");
        return switch (cmd) {
            case "stream" -> streamUrl(entityId);
            default -> Map.of("error", "unknown camera command: " + cmd);
        };
    }

    /**
     * Asks HA's stream component for an HLS playlist URL (camera/stream WS command, verified
     * against home-assistant/core's camera/__init__.py: {type, entity_id, format} -> {url}).
     *
     * KNOWN GAP: the returned URL is relative to homeassistant.url and is short-lived/signed.
     * In Supervisor-managed addon mode homeassistant.url resolves to the internal
     * http://supervisor/core proxy, which is not reachable by a client outside the addon's
     * network — this command works as-is in standalone dev mode, but exposing HA camera
     * streams to the cloud/mobile app from inside an addon needs its own follow-up (e.g.
     * proxying the HLS segments through the gateway itself, the way /snapshot already does
     * for single frames).
     */
    private Map<String, Object> streamUrl(String entityId) {
        try {
            JsonNode result = haInterface
                    .sendCommandWait("camera/stream", Map.of("entity_id", entityId, "format", "hls"))
                    .orTimeout(10, TimeUnit.SECONDS).join();
            String relativeUrl = result != null ? result.path("url").asText(null) : null;
            if (relativeUrl == null) return Map.of("error", "Home Assistant did not return a stream URL");
            return Map.of("url", connectionConfig.getHttpBaseUrl() + relativeUrl);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("Home Assistant: camera/stream failed for '{}': {}", entityId, cause.getMessage());
            return Map.of("error", cause.getMessage() != null ? cause.getMessage() : "camera/stream failed");
        }
    }
}

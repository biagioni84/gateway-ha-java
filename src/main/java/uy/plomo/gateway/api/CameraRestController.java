package uy.plomo.gateway.api;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uy.plomo.gateway.device.Device;
import uy.plomo.gateway.device.DeviceService;
import uy.plomo.gateway.homeassistant.camera.HomeAssistantCameraController;
import uy.plomo.gateway.homeassistant.camera.HomeAssistantCameraService;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST endpoints for camera-specific operations.
 *
 * Snapshot returns image bytes (image/jpeg), so it cannot go through the standard
 * GatewayApiService routing (which returns Map<String,Object>). This dedicated
 * controller handles it with a ResponseEntity<byte[]>.
 *
 * Cameras are Device.protocol="ha" entities whose node starts with "camera." — camera
 * setup (adding/discovering) happens in the Home Assistant UI; this controller only lists
 * and proxies.
 *
 * Network-level camera management:
 *   GET    /api/v1/cameras          — list all camera devices
 *   DELETE /api/v1/cameras/:dev     — remove a camera device row
 *
 * Per-device:
 *   GET    /api/v1/:dev/snapshot    — JPEG snapshot, proxied from Home Assistant
 */
@Tag(name = "04. Cameras", description = "Camera management and snapshot proxy")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CameraRestController {

    private final HomeAssistantCameraController haCameraController;
    private final HomeAssistantCameraService    haCameraService;
    private final DeviceService                 deviceService;
    private final GatewayApiService             api;

    // ── Network-level ─────────────────────────────────────────────────────────

    @GetMapping("/cameras")
    public Map<String, Object> listCameras() {
        List<Map<String, Object>> parsed = deviceService.findByProtocol("ha").stream()
                .filter(dev -> dev.getNode() != null && dev.getNode().startsWith("camera."))
                .map(dev -> haCameraController.parseDevice(dev.getId(), dev))
                .toList();
        return Map.of("cameras", parsed, "count", parsed.size());
    }

    @DeleteMapping("/cameras/{dev}")
    public Map<String, Object> deleteCamera(@PathVariable String dev) {
        return api.deleteDevice(dev);
    }

    // ── Per-device ────────────────────────────────────────────────────────────

    /**
     * Proxy a JPEG snapshot from Home Assistant for the given device.
     * Returns 404 if the device is not found or is not a camera.
     * Returns 502 if Home Assistant is unreachable or has no frame yet.
     */
    @GetMapping("/{dev}/snapshot")
    public ResponseEntity<byte[]> snapshot(@PathVariable String dev) {
        Optional<Device> opt = deviceService.findById(dev);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        Device device = opt.get();

        if (!"ha".equals(device.getProtocol()) || device.getNode() == null
                || !device.getNode().startsWith("camera.")) {
            return ResponseEntity.notFound().build();
        }

        byte[] bytes = haCameraService.getSnapshot(device.getNode());
        if (bytes == null || bytes.length == 0) {
            return ResponseEntity.status(502).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(bytes);
    }
}

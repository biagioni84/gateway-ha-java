package uy.plomo.gateway.api;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uy.plomo.gateway.camera.CameraController;
import uy.plomo.gateway.camera.CameraService;
import uy.plomo.gateway.device.Device;
import uy.plomo.gateway.device.DeviceService;
import uy.plomo.gateway.homeassistant.camera.HomeAssistantCameraController;
import uy.plomo.gateway.homeassistant.camera.HomeAssistantCameraService;

import java.util.ArrayList;
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
 * Cameras now come from two sources side by side:
 *   - protocol="camera": the original go2rtc-backed cameras (unchanged, still supported).
 *   - protocol="ha", node starting with "camera.": Home Assistant-managed cameras.
 *
 * Camera setup (adding/discovering a camera) now happens in the Home Assistant UI for
 * HA-managed cameras — this controller only lists and proxies, it doesn't register new
 * cameras anymore. (The old go2rtc add/discover REST endpoints were dropped as part of the
 * Home Assistant migration; CameraController still has the underlying methods for the
 * go2rtc path if that's ever needed again, they're just no longer exposed here.)
 *
 * Network-level camera management:
 *   GET    /api/v1/cameras          — list all camera devices (both sources)
 *   DELETE /api/v1/cameras/:dev     — remove a camera device row
 *
 * Per-device:
 *   GET    /api/v1/:dev/snapshot    — JPEG snapshot, proxied from go2rtc or Home Assistant
 */
@Tag(name = "04. Cameras", description = "Camera management and snapshot proxy")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CameraRestController {

    private final CameraController              cameraController;
    private final CameraService                  cameraService;
    private final HomeAssistantCameraController  haCameraController;
    private final HomeAssistantCameraService      haCameraService;
    private final DeviceService                  deviceService;
    private final GatewayApiService               api;

    // ── Network-level ─────────────────────────────────────────────────────────

    @GetMapping("/cameras")
    public Map<String, Object> listCameras() {
        List<Map<String, Object>> parsed = new ArrayList<>();
        deviceService.findByProtocol("camera")
                .forEach(dev -> parsed.add(cameraController.parseDevice(dev.getId(), dev)));
        deviceService.findByProtocol("ha").stream()
                .filter(dev -> dev.getNode() != null && dev.getNode().startsWith("camera."))
                .forEach(dev -> parsed.add(haCameraController.parseDevice(dev.getId(), dev)));
        return Map.of("cameras", parsed, "count", parsed.size());
    }

    @DeleteMapping("/cameras/{dev}")
    public Map<String, Object> deleteCamera(@PathVariable String dev) {
        return api.deleteDevice(dev);
    }

    // ── Per-device ────────────────────────────────────────────────────────────

    /**
     * Proxy a JPEG snapshot for the given device, from go2rtc or Home Assistant depending
     * on which backend owns it.
     * Returns 404 if the device is not found or is not a camera.
     * Returns 502 if the backend is unreachable or has no frame yet.
     */
    @GetMapping("/{dev}/snapshot")
    public ResponseEntity<byte[]> snapshot(@PathVariable String dev) {
        Optional<Device> opt = deviceService.findById(dev);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        Device device = opt.get();

        byte[] bytes;
        if ("camera".equals(device.getProtocol())) {
            String streamName = device.getNode();
            if (streamName == null) return ResponseEntity.notFound().build();
            bytes = cameraService.getSnapshot(streamName);
        } else if ("ha".equals(device.getProtocol()) && device.getNode() != null
                && device.getNode().startsWith("camera.")) {
            bytes = haCameraService.getSnapshot(device.getNode());
        } else {
            return ResponseEntity.notFound().build();
        }

        if (bytes == null || bytes.length == 0) {
            return ResponseEntity.status(502).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(bytes);
    }
}

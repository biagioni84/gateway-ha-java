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
 * Network-level camera management:
 *   GET  /api/v1/cameras           — list all camera devices
 *   POST /api/v1/cameras           — add camera manually { name, src }
 *   POST /api/v1/cameras/discover  — ONVIF discovery    { username?, password? }
 *
 * Per-device:
 *   GET  /api/v1/{dev}/snapshot    — JPEG snapshot (proxied from go2rtc)
 */
@Tag(name = "04. Cameras", description = "Camera management and snapshot proxy")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CameraRestController {

    private final CameraController cameraController;
    private final CameraService    cameraService;
    private final DeviceService    deviceService;
    private final GatewayApiService api;

    // ── Network-level ─────────────────────────────────────────────────────────

    @GetMapping("/cameras")
    public Map<String, Object> listCameras() {
        List<Device> cameras = deviceService.findByProtocol("camera");
        List<Map<String, Object>> parsed = cameras.stream()
                .map(dev -> cameraController.parseDevice(dev.getId(), dev))
                .toList();
        return Map.of("cameras", parsed, "count", parsed.size());
    }

    /**
     * Add a camera. Two modes:
     *   RTSP/RTMP/other: { "name": "Front Door", "src": "rtsp://user:pass@192.168.1.50/stream1" }
     *   ONVIF by IP:     { "name": "Front Door", "ip": "192.168.1.50", "username": "admin", "password": "12345" }
     */
    @PostMapping("/cameras")
    public Map<String, Object> addCamera(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) return Map.of("error", "body required");
        String name = api.str(body, "name");
        String type = api.str(body, "type");
        String ip   = api.str(body, "ip");
        String src  = api.str(body, "src");

        if ("ONVIF".equalsIgnoreCase(type) || ip != null) {
            return cameraController.addOnvifCamera(
                    name, ip,
                    api.str(body, "username"),
                    api.str(body, "password"),
                    api.str(body, "managementUrl"));
        }
        // Raw source URL mode
        return cameraController.addCamera(name, src);
    }

    /**
     * Scan for ONVIF cameras via WS-Discovery. No credentials needed.
     * Returns discovered devices with their IPs and whether they are already registered.
     * Use POST /cameras with { ip, username, password } to register a found device.
     */
    @PostMapping("/cameras/discover")
    public Map<String, Object> discover() {
        return cameraController.discoverCameras();
    }

    @DeleteMapping("/cameras/{dev}")
    public Map<String, Object> deleteCamera(@PathVariable String dev) {
        return api.deleteDevice(dev);
    }

    // ── Per-device ────────────────────────────────────────────────────────────

    /**
     * Proxy a JPEG snapshot from go2rtc for the given device.
     * Returns 404 if the device is not found or is not a camera.
     * Returns 502 if go2rtc is unreachable or the stream has no frame yet.
     */
    @GetMapping("/{dev}/snapshot")
    public ResponseEntity<byte[]> snapshot(@PathVariable String dev) {
        Optional<Device> opt = deviceService.findById(dev);
        if (opt.isEmpty() || !"camera".equals(opt.get().getProtocol())) {
            return ResponseEntity.notFound().build();
        }
        String streamName = opt.get().getNode();
        if (streamName == null) return ResponseEntity.notFound().build();

        byte[] bytes = cameraService.getSnapshot(streamName);
        if (bytes == null || bytes.length == 0) {
            return ResponseEntity.status(502).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(bytes);
    }
}

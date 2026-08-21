package uy.plomo.gateway.camera;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uy.plomo.gateway.device.Device;
import uy.plomo.gateway.device.DeviceService;

import java.util.*;

/**
 * High-level camera operations.
 *
 * Mirrors ZWaveController / ZigbeeController / MatterController for protocol integration.
 * Camera devices use:
 *   Device.protocol  = "camera"
 *   Device.type      = "camera"
 *   Device.node      = go2rtc stream name (e.g. "front_door")
 *   Device.attributes["Camera"]["src"]  = source URL registered in go2rtc
 *   Device.attributes["Camera"]["managementUrl"] = ONVIF management URL (if applicable)
 *
 * On startup, all camera devices in the DB are re-registered into go2rtc so that
 * a go2rtc restart does not permanently lose streams.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CameraController {

    private final CameraService          cameraService;
    private final OnvifDiscoveryService  onvifDiscovery;
    private final DeviceService          deviceService;

    @Value("${camera.enabled:true}")
    private boolean enabled;

    @Value("${camera.max.streams:8}")
    private int maxStreams;

    // ── Startup ───────────────────────────────────────────────────────────────

    /**
     * Re-register all camera devices into go2rtc on startup.
     * go2rtc's dynamic API is in-memory only — streams are lost on its restart.
     */
    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("Camera subsystem disabled (camera.enabled=false)");
            return;
        }
        if (!cameraService.isRunning()) {
            log.warn("Camera: go2rtc not reachable at startup — streams will be registered when it comes up");
            return;
        }
        List<Device> cameras = deviceService.findByProtocol("camera");
        for (Device dev : cameras) {
            String streamName = dev.getNode();
            String src = (String) dev.getAttribute("Camera", "src");
            if (streamName != null && src != null) {
                cameraService.addStream(streamName, src);
            }
        }
        log.info("Camera: re-registered {} stream(s) into go2rtc", cameras.size());
    }

    // ── Summary view ──────────────────────────────────────────────────────────

    /**
     * Flat summary map consistent with other protocol controllers.
     * `available` reflects whether the stream is currently registered in go2rtc.
     */
    public Map<String, Object> parseDevice(String id, Device dev) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id",       id);
        out.put("protocol", "camera");
        out.put("name",     dev.getName());
        out.put("node",     dev.getNode());
        out.put("type",     "camera");

        String streamName = dev.getNode();
        boolean available = streamName != null && cameraService.isStreamRegistered(streamName);
        out.put("available", available);
        out.put("status",    available ? "streaming" : "offline");
        out.put("snapshot",  streamName != null
                ? cameraService.getBaseUrl() + "/api/frame.jpeg?src=" + streamName : null);
        out.put("stream",    streamName != null ? cameraService.hlsUrl(streamName) : null);
        return out;
    }

    // ── Device commands ───────────────────────────────────────────────────────

    public Map<String, Object> handleDeviceCommand(
            Device dev, String cmd, String method, Map<String, Object> body) {
        return switch (cmd) {
            case "stream"   -> Map.of("url", cameraService.hlsUrl(dev.getNode()),
                                      "webrtc", cameraService.webrtcUrl(dev.getNode()));
            case "restart"  -> restartStream(dev);
            default         -> Map.of("error", "unknown camera command: " + cmd);
        };
    }

    private Map<String, Object> restartStream(Device dev) {
        String name = dev.getNode();
        String src  = (String) dev.getAttribute("Camera", "src");
        if (name == null || src == null)
            return Map.of("error", "camera device is missing stream name or src");
        cameraService.removeStream(name);
        cameraService.addStream(name, src);
        return Map.of("status", "ok");
    }

    // ── Add camera manually ───────────────────────────────────────────────────

    /**
     * Add a camera by explicit RTSP/ONVIF/RTMP URL.
     *
     * @param name display name (e.g. "Front Door")
     * @param src  source URL for go2rtc (e.g. "rtsp://user:pass@192.168.1.50/stream1"
     *             or "onvif://user:pass@192.168.1.50/")
     */
    public Map<String, Object> addCamera(String name, String src) {
        if (name == null || name.isBlank()) return Map.of("error", "name is required");
        if (src  == null || src.isBlank())  return Map.of("error", "src is required");

        List<Device> existing = deviceService.findByProtocol("camera");
        if (existing.size() >= maxStreams)
            return Map.of("error", "max concurrent streams reached (" + maxStreams + ")");

        String streamName = toStreamName(name);

        // Deduplicate: reject if a camera with the same stream name already exists
        boolean duplicate = existing.stream()
                .anyMatch(d -> streamName.equals(d.getNode()));
        if (duplicate) return Map.of("error", "camera with name '" + name + "' already exists");

        Device dev = new Device();
        dev.setProtocol("camera");
        dev.setType("camera");
        dev.setName(name);
        dev.setNode(streamName);
        dev.setAttribute("Camera", "src", src);
        deviceService.save(dev);

        cameraService.addStream(streamName, src);
        log.info("Camera: added '{}' stream={} src={}", name, streamName, src);

        return Map.of("status", "ok", "node", streamName, "stream", cameraService.hlsUrl(streamName));
    }

    // ── ONVIF discovery ───────────────────────────────────────────────────────

    /**
     * Scan the local network via WS-Discovery and return found ONVIF devices.
     * No credentials needed — discovery is unauthenticated broadcast.
     * Returns device IPs and management URLs; the caller decides which to register.
     */
    public Map<String, Object> discoverCameras() {
        List<OnvifDiscoveryService.DiscoveredCamera> found = onvifDiscovery.discover();

        List<Device> existing = deviceService.findByProtocol("camera");
        Set<String> existingNodes = new HashSet<>();
        existing.forEach(d -> { if (d.getNode() != null) existingNodes.add(d.getNode()); });

        List<Map<String, Object>> result = new ArrayList<>();
        for (OnvifDiscoveryService.DiscoveredCamera cam : found) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("ip",            cam.ip());
            entry.put("managementUrl", cam.managementUrl());
            entry.put("node",          cam.toStreamName());
            entry.put("registered",    existingNodes.contains(cam.toStreamName()));
            result.add(entry);
        }

        return Map.of("discovered", found.size(), "cameras", result);
    }

    /**
     * Register a discovered ONVIF camera by IP with credentials.
     * Builds an onvif:// source URL so go2rtc handles profile negotiation.
     *
     * @param name     display name (e.g. "Front Door")
     * @param ip       camera IP address as returned by discoverCameras()
     * @param username ONVIF username (may be null for cameras without auth)
     * @param password ONVIF password (may be null)
     * @param managementUrl optional management URL from discovery (stored for reference)
     */
    public Map<String, Object> addOnvifCamera(String name, String ip,
            String username, String password, String managementUrl) {
        if (ip == null || ip.isBlank()) return Map.of("error", "ip is required");
        String src = buildOnvifSrc(ip, username, password);
        Map<String, Object> result = addCamera(name != null ? name : "Camera " + ip, src);
        // store extra metadata if registration succeeded
        if ("ok".equals(result.get("status"))) {
            String streamName = (String) result.get("node");
            deviceService.findByNode(streamName).ifPresent(dev -> {
                dev.setAttribute("Camera", "ip",            ip);
                if (managementUrl != null)
                    dev.setAttribute("Camera", "managementUrl", managementUrl);
                deviceService.save(dev);
            });
        }
        return result;
    }

    private static String buildOnvifSrc(String ip, String username, String password) {
        if (username != null && !username.isBlank()) {
            return "onvif://" + username + ":" + (password != null ? password : "") + "@" + ip + "/";
        }
        return "onvif://" + ip + "/";
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Derive a safe go2rtc stream name from a display name (lowercase, spaces→underscores). */
    static String toStreamName(String name) {
        return name.toLowerCase(java.util.Locale.ROOT)
                   .replaceAll("[^a-z0-9_]", "_")
                   .replaceAll("_+", "_")
                   .replaceAll("^_|_$", "");
    }
}

package uy.plomo.gateway.camera;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.Map;

/**
 * Client wrapper for the go2rtc REST API (http://localhost:1984 by default).
 *
 * go2rtc API surface used here:
 *   GET  /api/streams                 → list all registered streams
 *   PUT  /api/streams?name=N&src=URL  → add / update a stream
 *   DELETE /api/streams?name=N        → remove a stream
 *   GET  /api/frame.jpeg?src=N        → JPEG snapshot of stream N
 *
 * HLS playlist for stream N is served at: {baseUrl}/{name}/index.m3u8
 */
@Component
@Slf4j
public class CameraService {

    @Value("${camera.go2rtc.url:http://localhost:1984}")
    private String baseUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    // ── Health ────────────────────────────────────────────────────────────────

    /** Returns true if go2rtc is reachable. */
    public boolean isRunning() {
        try {
            restTemplate.getForEntity(baseUrl + "/api/streams", Map.class);
            return true;
        } catch (RestClientException e) {
            return false;
        }
    }

    // ── Stream management ─────────────────────────────────────────────────────

    /**
     * Register a stream in go2rtc.
     *
     * @param name  stream name used in all go2rtc URLs (e.g. "front_door")
     * @param src   source URL (RTSP, ONVIF, RTMP, etc.) e.g. "rtsp://user:pass@192.168.1.50/stream1"
     *              or "onvif://user:pass@192.168.1.50/" for auto-negotiated ONVIF
     */
    public void addStream(String name, String src) {
        try {
            String url = baseUrl + "/api/streams?name={name}&src={src}";
            restTemplate.put(url, null, name, src);
            log.info("Camera: registered stream '{}' src={}", name, src);
        } catch (RestClientException e) {
            log.error("Camera: failed to add stream '{}': {}", name, e.getMessage());
        }
    }

    /** Remove a stream from go2rtc. No-op if not registered. */
    public void removeStream(String name) {
        try {
            restTemplate.delete(baseUrl + "/api/streams?name={name}", name);
            log.info("Camera: removed stream '{}'", name);
        } catch (RestClientException e) {
            log.warn("Camera: failed to remove stream '{}': {}", name, e.getMessage());
        }
    }

    /**
     * Returns the map of all registered streams from go2rtc.
     * Keys are stream names; values are go2rtc stream descriptors.
     * Returns an empty map if go2rtc is unreachable.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> listStreams() {
        try {
            ResponseEntity<Map> resp = restTemplate.getForEntity(baseUrl + "/api/streams", Map.class);
            if (resp.getBody() != null) return resp.getBody();
        } catch (RestClientException e) {
            log.debug("Camera: go2rtc unreachable: {}", e.getMessage());
        }
        return Collections.emptyMap();
    }

    /** Returns true if the stream name is currently registered in go2rtc. */
    public boolean isStreamRegistered(String name) {
        return listStreams().containsKey(name);
    }

    // ── Media ─────────────────────────────────────────────────────────────────

    /**
     * Fetch a JPEG snapshot from go2rtc for the given stream.
     * Returns the raw bytes, or null on error.
     */
    public byte[] getSnapshot(String name) {
        try {
            ResponseEntity<byte[]> resp = restTemplate.getForEntity(
                    baseUrl + "/api/frame.jpeg?src={name}", byte[].class, name);
            return resp.getBody();
        } catch (RestClientException e) {
            log.warn("Camera: snapshot failed for '{}': {}", name, e.getMessage());
            return null;
        }
    }

    /**
     * Returns the public HLS playlist URL for the given stream.
     * The URL points to go2rtc's built-in HLS server.
     */
    public String hlsUrl(String name) {
        return baseUrl + "/" + name + "/index.m3u8";
    }

    /** Returns the public WebRTC URL for the given stream. */
    public String webrtcUrl(String name) {
        return baseUrl + "/" + name + "/webrtc";
    }

    /** Returns the raw go2rtc base URL (for use in direct UI links). */
    public String getBaseUrl() {
        return baseUrl;
    }
}

package uy.plomo.gateway.homeassistant.camera;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import uy.plomo.gateway.homeassistant.HomeAssistantConnectionConfig;

/**
 * REST client for Home Assistant's camera snapshot proxy.
 *
 *   GET {baseUrl}/api/camera_proxy/{entity_id}  (Authorization: Bearer token) -> JPEG bytes
 *
 * Replaces the old go2rtc-backed CameraService for HA-managed cameras — the gateway no
 * longer runs its own media proxy; it fetches server-side from HA and hands the bytes back.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class HomeAssistantCameraService {

    private final HomeAssistantConnectionConfig connectionConfig;

    private final RestTemplate restTemplate = new RestTemplate();

    /** Fetch a JPEG snapshot for the given camera entity. Returns null on error. */
    public byte[] getSnapshot(String entityId) {
        try {
            String url = connectionConfig.getHttpBaseUrl() + "/api/camera_proxy/" + entityId;
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(connectionConfig.getToken());
            ResponseEntity<byte[]> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
            return resp.getBody();
        } catch (RestClientException e) {
            log.warn("Home Assistant: camera snapshot failed for '{}': {}", entityId, e.getMessage());
            return null;
        }
    }
}

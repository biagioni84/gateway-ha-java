package uy.plomo.gateway.homeassistant;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Resolves which Home Assistant integration owns a given entity (its "platform", e.g.
 * "zwave_js", "zha", "matter") via config/entity_registry/get.
 *
 * Needed wherever behavior must branch per owning integration — e.g. lock user-code
 * management, where each integration exposes a different (or no) service surface.
 * Looked up live rather than cached on the Device row, since a device could in principle
 * be re-paired under a different integration.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class HomeAssistantEntityRegistry {

    private final HomeAssistantInterface haInterface;

    private final ConcurrentHashMap<String, String> platformCache = new ConcurrentHashMap<>();

    /** Returns the owning integration domain (e.g. "zwave_js"), or null if it couldn't be resolved. */
    public String resolvePlatform(String entityId) {
        String cached = platformCache.get(entityId);
        if (cached != null) return cached;
        try {
            JsonNode result = haInterface
                    .sendCommandWait("config/entity_registry/get", Map.of("entity_id", entityId))
                    .orTimeout(10, TimeUnit.SECONDS)
                    .join();
            String platform = result != null ? result.path("platform").asText(null) : null;
            if (platform != null) platformCache.put(entityId, platform);
            return platform;
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("Home Assistant: failed to resolve owning integration for {}: {}", entityId, cause.getMessage());
            return null;
        }
    }
}

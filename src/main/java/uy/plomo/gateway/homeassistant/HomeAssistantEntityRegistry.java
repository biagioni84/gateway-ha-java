package uy.plomo.gateway.homeassistant;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Resolves Home Assistant entity/device registry info needed to route lock PIN code
 * management to the right integration: which HA integration owns an entity (its "platform"),
 * and for Zigbee entities without a clean high-level service, the underlying radio
 * identifiers needed to talk to the device more directly — ZHA's IEEE address, or
 * Zigbee2MQTT's MQTT friendly_name.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class HomeAssistantEntityRegistry {

    private final HomeAssistantInterface haInterface;

    private record EntityInfo(String platform, String deviceId) {}

    private final ConcurrentHashMap<String, EntityInfo> entityCache = new ConcurrentHashMap<>();

    /** Returns the owning integration domain (e.g. "zwave_js", "zha", "mqtt"), or null if it couldn't be resolved. */
    public String resolvePlatform(String entityId) {
        EntityInfo info = resolveEntityInfo(entityId);
        return info != null ? info.platform() : null;
    }

    /**
     * Resolves a ZHA-owned entity's device IEEE address, from the device registry's
     * identifiers (["zha", "&lt;ieee&gt;"]). Returns null if the entity isn't ZHA-owned or the
     * device can't be found.
     * VERIFY: the "zha" identifier domain string is the standard HA device-registry naming
     * convention for this integration — not directly confirmed against a live instance here.
     */
    public String resolveZhaIeee(String entityId) {
        JsonNode device = findDevice(entityId);
        if (device == null) return null;
        for (JsonNode idPair : device.path("identifiers")) {
            if (idPair.isArray() && idPair.size() == 2 && "zha".equals(idPair.path(0).asText())) {
                return idPair.path(1).asText(null);
            }
        }
        return null;
    }

    /**
     * Resolves the Zigbee2MQTT "friendly_name" (the MQTT topic segment, e.g.
     * "zigbee2mqtt/&lt;friendly_name&gt;/set") for an entity whose device was created by Z2M's HA
     * MQTT discovery — identified by a device identifier ["mqtt", "zigbee2mqtt_&lt;ieee&gt;"].
     * Returns null if the entity isn't Zigbee2MQTT-sourced (including for other, unrelated
     * MQTT-integration entities, which also report platform "mqtt" but aren't Z2M).
     *
     * Uses the device registry's "name" as a best-effort proxy for the live Z2M friendly_name
     * (Z2M republishes its discovery config, updating this, when a device is renamed) — falls
     * back to the ieee-derived default name if unavailable, since that's Z2M's default
     * friendly_name for a device that has never been renamed.
     */
    public String resolveZigbee2MqttFriendlyName(String entityId) {
        JsonNode device = findDevice(entityId);
        if (device == null) return null;
        String ieee = null;
        boolean isZ2m = false;
        for (JsonNode idPair : device.path("identifiers")) {
            if (idPair.isArray() && idPair.size() == 2 && "mqtt".equals(idPair.path(0).asText())) {
                String value = idPair.path(1).asText("");
                if (value.startsWith("zigbee2mqtt_")) {
                    isZ2m = true;
                    ieee = value.substring("zigbee2mqtt_".length());
                }
            }
        }
        if (!isZ2m) return null;
        String name = device.path("name").asText(null);
        return (name != null && !name.isBlank()) ? name : ieee;
    }

    private EntityInfo resolveEntityInfo(String entityId) {
        EntityInfo cached = entityCache.get(entityId);
        if (cached != null) return cached;
        try {
            JsonNode result = haInterface
                    .sendCommandWait("config/entity_registry/get", Map.of("entity_id", entityId))
                    .orTimeout(10, TimeUnit.SECONDS)
                    .join();
            if (result == null) return null;
            EntityInfo info = new EntityInfo(
                    result.path("platform").asText(null),
                    result.path("device_id").asText(null));
            if (info.platform() != null) entityCache.put(entityId, info);
            return info;
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("Home Assistant: failed to resolve entity registry info for {}: {}", entityId, cause.getMessage());
            return null;
        }
    }

    /**
     * Fetches the device registry entry owning this entity. Home Assistant's WS API has no
     * single-device lookup (verified against a full grep of home-assistant/core's
     * websocket_command registrations — only config/device_registry/list exists), so this
     * fetches the full list and filters client-side. Not cached — device names/identifiers
     * (particularly a Zigbee2MQTT rename) should stay live.
     */
    private JsonNode findDevice(String entityId) {
        EntityInfo info = resolveEntityInfo(entityId);
        String deviceId = info != null ? info.deviceId() : null;
        if (deviceId == null) return null;
        try {
            JsonNode devices = haInterface.sendCommandWait("config/device_registry/list", null)
                    .orTimeout(10, TimeUnit.SECONDS)
                    .join();
            if (devices != null && devices.isArray()) {
                for (JsonNode d : devices) {
                    if (deviceId.equals(d.path("id").asText(null))) return d;
                }
            }
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("Home Assistant: failed to look up device registry for {}: {}", entityId, cause.getMessage());
        }
        return null;
    }
}

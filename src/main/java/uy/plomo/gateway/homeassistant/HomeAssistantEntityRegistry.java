package uy.plomo.gateway.homeassistant;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Resolves Home Assistant entity/device/area registry info needed to:
 *   - route lock PIN code management to the right integration (platform, ZHA ieee, Z2M name)
 *   - group entities into the HAv1 summary's per-physical-device rows (device_id, entity_category,
 *     manufacturer/model/area) — see GatewayApiService.getSummary()
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class HomeAssistantEntityRegistry {

    private final HomeAssistantInterface haInterface;

    private record EntityInfo(String platform, String deviceId, String entityCategory, String areaId) {}

    /** manufacturer/model/name/areaId as they are in HA's device registry — areaId here is the
     *  device's own area; an entity's own area_id (see EntityInfo) overrides it when present,
     *  matching HA's own area-resolution precedence. */
    public record HaDeviceInfo(String manufacturer, String model, String name, String areaId) {}

    private final ConcurrentHashMap<String, EntityInfo> entityCache = new ConcurrentHashMap<>();

    // Bulk-loaded by primeCache() for HAv1 summary grouping — separate from entityCache/findDevice()
    // below (used by the pincode/ZHA/Z2M paths, which fetch live to catch Z2M renames promptly).
    // A grouped device's manufacturer/model/area only refresh on the next primeCache() call
    // (gateway restart, or the next initial HA sync) — acceptable staleness for display fields.
    private final ConcurrentHashMap<String, HaDeviceInfo> deviceCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> areaNameCache = new ConcurrentHashMap<>();

    /** Returns the owning integration domain (e.g. "zwave_js", "zha", "mqtt"), or null if it couldn't be resolved. */
    public String resolvePlatform(String entityId) {
        EntityInfo info = resolveEntityInfo(entityId);
        return info != null ? info.platform() : null;
    }

    /**
     * Returns the entity registry's entity_category ("diagnostic", "config", or null for a
     * primary entity).
     */
    public String resolveEntityCategory(String entityId) {
        EntityInfo info = resolveEntityInfo(entityId);
        return info != null ? info.entityCategory() : null;
    }

    /** Returns the HA device registry id this entity belongs to, or null (helpers, some templates). */
    public String resolveHaDeviceId(String entityId) {
        EntityInfo info = resolveEntityInfo(entityId);
        return info != null ? info.deviceId() : null;
    }

    /**
     * Returns the effective area for an entity: its own area_id if explicitly set, otherwise its
     * device's area_id — mirrors HA's own precedence (an entity-level area override wins).
     */
    public String resolveAreaId(String entityId) {
        EntityInfo info = resolveEntityInfo(entityId);
        if (info == null) return null;
        if (info.areaId() != null) return info.areaId();
        HaDeviceInfo device = resolveHaDeviceInfo(info.deviceId());
        return device != null ? device.areaId() : null;
    }

    public String resolveAreaName(String areaId) {
        return areaId != null ? areaNameCache.get(areaId) : null;
    }

    public HaDeviceInfo resolveHaDeviceInfo(String haDeviceId) {
        return haDeviceId != null ? deviceCache.get(haDeviceId) : null;
    }

    /**
     * Bulk-loads the entity, device, and area registries in three calls and primes the caches
     * above, so the initial device sync (all of get_states, at once) doesn't do a round-trip per
     * entity just to find out its category/device/area. Entities that show up later via
     * state_changed still resolve lazily through resolveEntityInfo()'s single-entity lookup.
     * Safe to call more than once — later calls just refresh the caches.
     */
    public void primeCache() {
        primeAreaCache();
        primeDeviceCache();
        primeEntityCache();
    }

    private void primeEntityCache() {
        try {
            JsonNode list = haInterface.sendCommandWait("config/entity_registry/list", null)
                    .orTimeout(15, TimeUnit.SECONDS)
                    .join();
            if (list == null || !list.isArray()) return;
            int count = 0;
            for (JsonNode entry : list) {
                String entityId = entry.path("entity_id").asText(null);
                if (entityId == null) continue;
                entityCache.put(entityId, new EntityInfo(
                        entry.path("platform").asText(null),
                        entry.path("device_id").asText(null),
                        entry.path("entity_category").asText(null),
                        entry.path("area_id").asText(null)));
                count++;
            }
            log.info("Home Assistant: primed entity registry cache — {} entities", count);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("Home Assistant: failed to bulk-load entity registry — falling back to " +
                    "per-entity lookups: {}", cause.getMessage());
        }
    }

    private void primeDeviceCache() {
        try {
            JsonNode list = haInterface.sendCommandWait("config/device_registry/list", null)
                    .orTimeout(15, TimeUnit.SECONDS)
                    .join();
            if (list == null || !list.isArray()) return;
            int count = 0;
            for (JsonNode entry : list) {
                String id = entry.path("id").asText(null);
                if (id == null) continue;
                deviceCache.put(id, new HaDeviceInfo(
                        entry.path("manufacturer").asText(null),
                        entry.path("model").asText(null),
                        entry.path("name_by_user").asText(entry.path("name").asText(null)),
                        entry.path("area_id").asText(null)));
                count++;
            }
            log.info("Home Assistant: primed device registry cache — {} devices", count);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("Home Assistant: failed to bulk-load device registry: {}", cause.getMessage());
        }
    }

    private void primeAreaCache() {
        try {
            JsonNode list = haInterface.sendCommandWait("config/area_registry/list", null)
                    .orTimeout(15, TimeUnit.SECONDS)
                    .join();
            if (list == null || !list.isArray()) return;
            int count = 0;
            for (JsonNode entry : list) {
                String id = entry.path("area_id").asText(null);
                if (id == null) continue;
                areaNameCache.put(id, entry.path("name").asText(null));
                count++;
            }
            log.info("Home Assistant: primed area registry cache — {} areas", count);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("Home Assistant: failed to bulk-load area registry: {}", cause.getMessage());
        }
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
                    result.path("device_id").asText(null),
                    result.path("entity_category").asText(null),
                    result.path("area_id").asText(null));
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
     * (particularly a Zigbee2MQTT rename) should stay live. (This is deliberately separate from
     * the deviceCache primeCache() populates for summary grouping, which tolerates staleness.)
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

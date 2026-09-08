package uy.plomo.gateway.homeassistant;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uy.plomo.gateway.device.Device;
import uy.plomo.gateway.device.DeviceService;
import uy.plomo.gateway.diagnostics.UnhandledFrameStore;
import uy.plomo.gateway.telemetry.TelemetryBuffer;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Handles spontaneous state_changed events pushed by Home Assistant.
 *
 * Registered into HomeAssistantInterface post-construction to break the circular dep
 * (mirrors MatterReportHandler/ZigbeeReportHandler pattern).
 *
 * Only entities whose domain HomeAssistantTypeMapper recognizes become gateway Devices —
 * automations, scripts, zones, persons, updates, etc. are not addressable devices and are
 * silently skipped.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class HomeAssistantReportHandler {

    private final HomeAssistantInterface      haInterface;
    private final HomeAssistantEntityRegistry entityRegistry;
    private final DeviceService               deviceService;
    private final TelemetryBuffer             telemetryBuffer;
    private final UnhandledFrameStore         unhandledFrameStore;

    @PostConstruct
    public void init() {
        haInterface.setReportHandler(this);
    }

    // ── Initial state load ────────────────────────────────────────────────────

    /** Called once after get_states populates the state cache. */
    public void onInitialStates(Collection<HAState> states) {
        // One bulk registry fetch up front so setup() below doesn't do a round-trip per entity
        // just to find out its device/area/category.
        entityRegistry.primeCache();
        states.forEach(this::setup);
    }

    // ── Event handling ────────────────────────────────────────────────────────

    public void handleEvent(String eventType, JsonNode data) {
        if ("state_changed".equals(eventType)) {
            handleStateChanged(data);
        } else {
            unhandledFrameStore.record("ha", entityIdOf(data), "event", eventType, null);
        }
    }

    private void handleStateChanged(JsonNode data) {
        if (data == null) return;
        String entityId = data.path("entity_id").asText(null);
        if (entityId == null) return;

        JsonNode newState = data.path("new_state");
        if (newState == null || newState.isMissingNode() || newState.isNull()) {
            log.debug("Home Assistant: entity {} removed", entityId);
            return;
        }

        HAState state = toHAState(entityId, newState);
        String type = HomeAssistantTypeMapper.inferType(state.domain(), state.attributes());
        if (type == null) return; // not represented as a gateway Device

        Optional<Device> existing = deviceService.findByNode(entityId);
        boolean isNew = existing.isEmpty();
        Device dev = existing.orElseGet(Device::new);
        populateDevice(dev, state, type, isNew);
        deviceService.save(dev);

        if (isNew) {
            notifyNewEntity(dev);
        } else {
            forwardEventIfConfigured(dev, entityId, state);
        }
    }

    // ── Device setup ──────────────────────────────────────────────────────────

    /** Create or refresh a DB entry for an HA entity. Mirrors MatterReportHandler.setup(). */
    private void setup(HAState state) {
        String type = HomeAssistantTypeMapper.inferType(state.domain(), state.attributes());
        if (type == null) return;

        Optional<Device> existing = deviceService.findByNode(state.entityId());
        boolean isNew = existing.isEmpty();
        Device dev = existing.orElseGet(Device::new);
        populateDevice(dev, state, type, isNew);
        deviceService.save(dev);

        if (isNew) {
            log.info("Home Assistant: created device entry for {} (name={} type={})",
                    state.entityId(), dev.getName(), type);
        }
    }

    private void populateDevice(Device dev, HAState state, String type, boolean isNew) {
        dev.setProtocol("ha");
        dev.setNode(state.entityId());
        dev.setType(type);
        if (isNew || dev.getName() == null) {
            String friendlyName = state.attributes() != null
                    ? state.attributes().path("friendly_name").asText(null) : null;
            dev.setName(friendlyName != null ? friendlyName : state.entityId());
        }
        applyState(dev, state);
        applyRegistryMeta(dev, state.entityId());
    }

    /**
     * Stashes registry info this entity's row needs for HAv1 summary grouping
     * (GatewayApiService.getSummary()): which physical HA device it belongs to, its
     * area, and its entity_category (diagnostic/config/primary). Stored regardless of
     * category — grouping/filtering happens when the summary is built, not here.
     */
    private void applyRegistryMeta(Device dev, String entityId) {
        String haDeviceId = entityRegistry.resolveHaDeviceId(entityId);
        String areaId = entityRegistry.resolveAreaId(entityId);
        String areaName = entityRegistry.resolveAreaName(areaId);
        String category = entityRegistry.resolveEntityCategory(entityId);
        HomeAssistantEntityRegistry.HaDeviceInfo haDevice = entityRegistry.resolveHaDeviceInfo(haDeviceId);

        dev.setAttribute("_meta", "ha_device_id", haDeviceId);
        dev.setAttribute("_meta", "area_id", areaId);
        dev.setAttribute("_meta", "area_name", areaName);
        dev.setAttribute("_meta", "entity_category", category);
        if (haDevice != null) {
            dev.setManufacturer(haDevice.manufacturer());
            dev.setModelId(haDevice.model());
        }
    }

    private void applyState(Device dev, HAState state) {
        String domain = state.domain();
        dev.setAttribute(domain, "state", state.state());
        JsonNode attrs = state.attributes();
        if (attrs != null && attrs.isObject()) {
            attrs.properties().forEach(e -> dev.setAttribute(domain, e.getKey(), jsonScalar(e.getValue())));
        }
    }

    // ── Telemetry forwarding ──────────────────────────────────────────────────

    private void notifyNewEntity(Device dev) {
        Map<String, Object> telEvent = new LinkedHashMap<>();
        telEvent.put("type",     "ha");
        telEvent.put("event",    "entity_added");
        telEvent.put("node-id",  dev.getNode());
        telEvent.put("name",     dev.getName());
        telEvent.put("dev_type", dev.getType());
        telemetryBuffer.add(telEvent);
    }

    private void forwardEventIfConfigured(Device dev, String entityId, HAState state) {
        List<String> fwdEvents = dev.getFwdEvents();
        if (fwdEvents == null || fwdEvents.isEmpty()) return;
        // HA's state_changed event carries no finer-grained sub-classification the way
        // Zigbee/Matter attribute reports do — "*" or "state" opts a device in to forwarding
        // every state change it has.
        if (!fwdEvents.contains("*") && !fwdEvents.contains("state")) return;

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type",    "ha");
        event.put("node-id", entityId);
        event.put("payload", Map.of(
                "cmd",   "state_changed",
                "state", state.state() == null ? "" : state.state()
        ));
        telemetryBuffer.add(event);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static HAState toHAState(String entityId, JsonNode s) {
        return new HAState(
                entityId,
                s.path("state").asText(null),
                s.path("attributes"),
                s.path("last_changed").asText(null),
                s.path("last_updated").asText(null));
    }

    private static String entityIdOf(JsonNode data) {
        return data != null ? data.path("entity_id").asText("") : "";
    }

    private static Object jsonScalar(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return null;
        if (n.isBoolean())            return n.asBoolean();
        if (n.isIntegralNumber())     return n.asLong();
        if (n.isFloatingPointNumber()) return n.asDouble();
        if (n.isTextual())            return n.asText();
        return n.toString(); // arrays/objects: store as a JSON string
    }
}

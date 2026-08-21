package uy.plomo.gateway.matter;

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

/**
 * Handles spontaneous events pushed by python-matter-server.
 *
 * Registered into MatterInterface post-construction to break the circular dep
 * (mirrors ZigbeeReportHandler pattern).
 *
 * Events:
 *   node_added        — new device commissioned
 *   node_removed      — device removed from fabric
 *   attribute_updated — device state changed (most frequent)
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MatterReportHandler {

    private final MatterInterface     matterInterface;
    private final DeviceService       deviceService;
    private final TelemetryBuffer     telemetryBuffer;
    private final UnhandledFrameStore unhandledFrameStore;

    @PostConstruct
    public void init() {
        matterInterface.setReportHandler(this);
    }

    // ── Initial node load ─────────────────────────────────────────────────────

    /** Called once after start_listening populates the node cache. */
    public void onInitialNodes(Collection<MatterNode> nodes) {
        nodes.forEach(this::setup);
    }

    // ── Event handling ────────────────────────────────────────────────────────

    public void handleEvent(String event, JsonNode data) {
        switch (event) {
            case "node_added"        -> {
                log.info("Matter: node added — nodeId={}", nodeId(data));
                if (data != null) {
                    long    id    = data.path("node_id").asLong();
                    boolean avail = data.path("available").asBoolean(true);
                    setup(new MatterNode(id, avail, data));
                    // Notify listeners that a new device was commissioned
                    deviceService.findByNode(String.valueOf(id)).ifPresent(dev -> {
                        Map<String, Object> telEvent = new LinkedHashMap<>();
                        telEvent.put("type",     "matter");
                        telEvent.put("event",    "node_added");
                        telEvent.put("node_id",  String.valueOf(id));
                        telEvent.put("name",     dev.getName());
                        telEvent.put("dev_type", dev.getType());
                        telemetryBuffer.add(telEvent);
                    });
                }
            }
            case "node_removed"      -> log.info("Matter: node removed — nodeId={}", nodeId(data));
            case "attribute_updated" -> handleAttributeUpdated(data);
            default -> unhandledFrameStore.record("matter", String.valueOf(nodeId(data)), event, event, null);
        }
    }

    // ── Device setup ──────────────────────────────────────────────────────────

    /**
     * Create a DB entry for a Matter node if one does not already exist.
     * Mirrors ZWaveController.setup().
     * Device.node stores the nodeId as a decimal string (e.g. "9").
     */
    private void setup(MatterNode node) {
        String nodeStr = String.valueOf(node.nodeId());
        if (deviceService.findByNode(nodeStr).isPresent()) {
            log.debug("Matter: node {} already in DB — skipping setup", nodeStr);
            return;
        }

        Device dev = new Device();
        dev.setProtocol("matter");
        dev.setNode(nodeStr);

        JsonNode attrs = node.raw() != null ? node.raw().path("attributes") : null;
        if (attrs != null && !attrs.isMissingNode()) {
            String nodeLabel = MatterController.textAttr(attrs, "0/40/14");
            String productName = MatterController.textAttr(attrs, "0/40/3");
            dev.setName((nodeLabel != null && !nodeLabel.isBlank()) ? nodeLabel : productName);
            dev.setManufacturer(MatterController.textAttr(attrs, "0/40/1"));
            dev.setManufacturerId(MatterController.vendorIdHex(attrs.path("0/40/2")));
            dev.setModelId(productName);
            dev.setType(MatterController.inferType(attrs));
        }

        deviceService.save(dev);
        log.info("Matter: created device entry for node {} (name={} type={})",
                nodeStr, dev.getName(), dev.getType());
    }

    // ── Attribute updates ─────────────────────────────────────────────────────

    private void handleAttributeUpdated(JsonNode data) {
        if (data == null) return;
        long   nodeId      = data.path("node_id").asLong();
        int    endpointId  = data.path("endpoint_id").asInt();
        int    clusterId   = data.path("cluster_id").asInt();
        int    attributeId = data.path("attribute_id").asInt();
        JsonNode value     = data.path("value");

        log.debug("Matter attribute_updated: node={} ep={} cluster={} attr={} value={}",
                nodeId, endpointId, clusterId, attributeId, value);

        // Persist to Device.attributes using path "ep/cluster/attr"
        String attrPath = endpointId + "/" + clusterId + "/" + attributeId;
        deviceService.findByNode(String.valueOf(nodeId)).ifPresent(dev -> {
            dev.setAttribute(
                    MatterProtocol.clusterName(clusterId),
                    MatterProtocol.attributeName(clusterId, attributeId),
                    value.isNull() ? null : value.asText());
            deviceService.save(dev);
            forwardEventIfConfigured(dev, nodeId, clusterId, attributeId, attrPath, value);
        });
    }

    private void forwardEventIfConfigured(Device dev, long nodeId,
            int clusterId, int attributeId, String attrPath, JsonNode value) {
        List<String> fwdEvents = dev.getFwdEvents();
        if (fwdEvents == null || fwdEvents.isEmpty()) return;

        boolean matches = fwdEvents.stream()
                .anyMatch(e -> MatterProtocol.matchesFwdEvent(e, clusterId, attributeId));
        if (!matches) return;

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type",    "matter");
        event.put("node-id", String.valueOf(nodeId));
        event.put("payload", Map.of(
                "cluster",   MatterProtocol.clusterName(clusterId),
                "attribute", MatterProtocol.attributeName(clusterId, attributeId),
                "attr",      attrPath,
                "value",     value.isNull() ? null : value.asText()
        ));
        telemetryBuffer.add(event);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static long nodeId(JsonNode data) {
        return data != null ? data.path("node_id").asLong(-1) : -1;
    }


}

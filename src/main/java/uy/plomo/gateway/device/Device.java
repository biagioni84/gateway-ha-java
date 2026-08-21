package uy.plomo.gateway.device;

import jakarta.persistence.*;
import lombok.Data;
import uy.plomo.gateway.util.JsonConverter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent representation of a device the gateway knows about.
 *
 * Key fields (indexed for fast lookup):
 *   node     — the Home Assistant entity_id, e.g. "lock.front_door"
 *   protocol — "ha" for everything backed by Home Assistant (including cameras)
 *   ieeeAddr — unused by protocol "ha"; kept for old rows from before the Home Assistant migration
 *
 * Nested data stored as JSON TEXT columns:
 *   attributes — {domain → {attrName → value}}
 *   pincodes   — {userId  → code}
 *   fwdEvents  — [eventName, ...]
 */
@Entity
@Table(name = "devices", indexes = {
        @Index(name = "idx_device_node",     columnList = "node"),
        @Index(name = "idx_device_ieee",     columnList = "ieee_addr"),
        @Index(name = "idx_device_protocol", columnList = "protocol")
})
@Data
public class Device {

    @Id
    private String id;

    private String protocol;    // "ha"
    private String name;

    private String node;        // Home Assistant entity_id, e.g. "lock.front_door"

    @Column(name = "ieee_addr")
    private String ieeeAddr;    // unused by protocol "ha" — retained for old pre-migration rows

    private String manufacturer;

    @Column(name = "manufacturer_id")
    private String manufacturerId;

    @Column(name = "product_type_id")
    private String productTypeId;

    @Column(name = "model_id")
    private String modelId;

    private String type;        // logical device type, e.g. "lock", "switch", "thermostat"

    private String descriptor;  // path to device template file

    @Column(name = "descriptor_source")
    private String descriptorSource;  // "manufacturer" | "generic" — how the descriptor was resolved

    /**
     * Cluster → attribute → value.
     * E.g. {"OnOff": {"OnOff": false}, "Basic": {"ManufacturerName": "Yale"}}
     */
    @Column(columnDefinition = "TEXT")
    @Convert(converter = JsonConverter.NestedStringObjectMap.class)
    private Map<String, Map<String, Object>> attributes = new HashMap<>();

    /**
     * UserID → PIN code string.
     * E.g. {"1": "1234", "2": "5678"}
     */
    @Column(columnDefinition = "TEXT")
    @Convert(converter = JsonConverter.StringStringMap.class)
    private Map<String, String> pincodes = new HashMap<>();

    /**
     * Event names this device should forward to the cloud as telemetry (e.g. "*", "state").
     */
    @Column(columnDefinition = "TEXT")
    @Convert(converter = JsonConverter.StringList.class)
    private List<String> fwdEvents = new ArrayList<>();

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Returns the attribute value, or null if absent. */
    public Object getAttribute(String cluster, String attrName) {
        if (attributes == null) return null;
        Map<String, Object> clusterMap = attributes.get(cluster);
        return clusterMap != null ? clusterMap.get(attrName) : null;
    }

    /** Sets (or replaces) a single attribute value. */
    public void setAttribute(String cluster, String attrName, Object value) {
        if (attributes == null) attributes = new HashMap<>();
        attributes.computeIfAbsent(cluster, k -> new HashMap<>()).put(attrName, value);
    }
}

package uy.plomo.gateway.device;

import jakarta.persistence.*;
import lombok.Data;
import uy.plomo.gateway.util.JsonConverter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent representation of a Z-Wave or Zigbee device.
 *
 * Key fields (indexed for fast lookup):
 *   node     — hex string used by both protocols, e.g. "0x38E4"
 *   ieeeAddr — 64-bit Zigbee EUI-64, e.g. "0015BC002F009BD9"
 *   protocol — "zwave" | "zigbee"
 *
 * Nested data stored as JSON TEXT columns:
 *   attributes — {cluster → {attrName → value}}
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

    private String protocol;    // "zwave" | "zigbee"
    private String name;

    private String node;        // e.g. "0x38E4"  (zwave + zigbee)

    @Column(name = "ieee_addr")
    private String ieeeAddr;    // e.g. "0015BC002F009BD9"  (zigbee only)

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
     * Endpoint definitions for Zigbee devices (stored as opaque JSON list).
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

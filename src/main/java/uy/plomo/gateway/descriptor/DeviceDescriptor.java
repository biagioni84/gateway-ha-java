package uy.plomo.gateway.descriptor;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * Base class for device descriptors loaded from JSON files.
 *
 * Concrete subclasses are selected by the "protocol" field:
 *   "zwave"   → ZWaveDescriptor
 *   "zigbee"  → ZigbeeDescriptor
 *
 * Adding a new protocol only requires:
 *   1. A new subclass that extends DeviceDescriptor
 *   2. A new @JsonSubTypes.Type entry below
 *   3. JSON files under resources/devices/{protocol}/
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "protocol",
        visible = true
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = ZWaveDescriptor.class,  name = "zwave"),
        @JsonSubTypes.Type(value = ZigbeeDescriptor.class, name = "zigbee"),
})
@JsonIgnoreProperties(ignoreUnknown = true)
@Getter @Setter @NoArgsConstructor
public abstract class DeviceDescriptor {

    /** Protocol identifier — also used as the Jackson type discriminator. */
    protected String protocol;

    /** Logical device type, e.g. "lock", "switch", "thermostat". */
    protected String type;

    /** Human-readable manufacturer name. */
    protected String manufacturer;

    /** Manufacturer's model identifier string. */
    protected String modelId;

    /** Short device label, e.g. "YRD226". */
    protected String label;

    /** Longer description, e.g. "Yale Real Living Assure Lock Touch Screen Deadbolt". */
    protected String description;

    /** Events this device should forward to MQTT (e.g. ["lock", "battery"]). */
    protected List<String> fwdEvents = List.of();

    /**
     * Returns true if this descriptor matches the given criteria map.
     * Each subclass defines its own matching rules (primary + fallback).
     *
     * @param criteria key→value pairs, e.g. {manufacturerId="0x0129", modelId="0x0600"}
     */
    public abstract boolean matches(Map<String, String> criteria);
}

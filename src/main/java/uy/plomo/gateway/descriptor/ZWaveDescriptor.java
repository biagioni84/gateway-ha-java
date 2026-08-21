package uy.plomo.gateway.descriptor;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * Device descriptor for Z-Wave devices.
 *
 * Matching priority:
 *   1. manufacturerId + devices[].{productType, productId}  (specific device match)
 *   2. generic + specific                                   (device-class fallback)
 *
 * A single file may cover multiple product variants via the devices[] array.
 * productTypeId is optional in criteria — if absent, only productId is checked.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Getter @Setter @NoArgsConstructor
public class ZWaveDescriptor extends DeviceDescriptor {

    /** Hex manufacturer ID as reported by the node, e.g. "0x0129". */
    private String manufacturerId;

    /**
     * Product variants covered by this descriptor.
     * Each entry has productType + productId (both as 0x-prefixed hex strings).
     * If empty, matching falls back to generic/specific.
     */
    private List<DeviceEntry> devices = List.of();

    /** Generic device class hex, e.g. "10". */
    private String generic;

    /** Specific device class hex, e.g. "01". */
    private String specific;

    /** GET commands to send during interview to populate initial device state. */
    private List<InterviewStep> interview = List.of();

    /**
     * Association groups the device supports.
     * Key is group number as a string (e.g. "1").
     * Groups with isLifeline=true are auto-bound to the controller during init.
     * If empty, falls back to the explicit bindings list.
     */
    private Map<String, AssociationGroup> associations = Map.of();

    /**
     * Explicit lifeline group numbers to bind to the controller.
     * Legacy fallback — prefer associations when possible.
     */
    private List<Integer> bindings = List.of();

    /** Descriptive configuration parameter metadata (for UI / validation). */
    private List<ParamInfo> paramInformation = List.of();

    /** Configuration parameters to imperatively apply during device init. */
    private List<ConfigParam> configuration = List.of();

    /** Initialization tasks to run after configuration. */
    private List<InitTask> init = List.of();

    /** Operational instructions for the end user or app. */
    private Metadata metadata;

    @Override
    public boolean matches(Map<String, String> criteria) {
        // Primary: manufacturer + devices[] match
        if (criteria.containsKey("manufacturerId") && !devices.isEmpty()) {
            if (!criteria.get("manufacturerId").equalsIgnoreCase(manufacturerId)) return false;
            String productTypeId = criteria.get("productTypeId");
            String modelId       = criteria.get("modelId");
            return devices.stream().anyMatch(d -> {
                boolean typeMatches = productTypeId == null
                        || d.getProductType() == null
                        || d.getProductType().equalsIgnoreCase(productTypeId);
                boolean idMatches   = modelId == null
                        || d.getProductId().equalsIgnoreCase(modelId);
                return typeMatches && idMatches;
            });
        }
        // Fallback: generic/specific device class match
        if (criteria.containsKey("generic") && criteria.containsKey("specific")) {
            return criteria.get("generic").equalsIgnoreCase(generic)
                    && criteria.get("specific").equalsIgnoreCase(specific);
        }
        return false;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the lifeline group numbers to bind to the controller.
     * Prefers associations (isLifeline=true) over the legacy bindings list.
     */
    public List<Integer> lifelineGroups() {
        if (!associations.isEmpty()) {
            return associations.entrySet().stream()
                    .filter(e -> Boolean.TRUE.equals(e.getValue().isLifeline()))
                    .map(e -> Integer.parseInt(e.getKey()))
                    .collect(java.util.stream.Collectors.toList());
        }
        return bindings;
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    /** One product variant covered by this descriptor. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Getter @Setter @NoArgsConstructor
    public static class DeviceEntry {
        /** Product type ID, e.g. "0x0800". */
        private String productType;

        /** Product ID, e.g. "0x0001". */
        private String productId;
    }

    /** One GET command to send during the interview phase. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Getter @Setter @NoArgsConstructor
    public static class InterviewStep {
        /** Z-Wave command class name, e.g. "DOOR_LOCK". JSON key: "class". */
        @JsonProperty("class")
        private String commandClass;

        /** Command name, e.g. "DOOR_LOCK_OPERATION_GET". */
        private String cmd;

        /** Optional extra bytes to append to the request frame, as hex strings e.g. ["01"]. */
        private List<String> data;
    }

    /** A single configuration parameter to write to the device. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Getter @Setter @NoArgsConstructor
    public static class ConfigParam {
        /** Parameter number as a hex string, e.g. "02". */
        private String param;

        /**
         * Value bytes as a list of hex strings, e.g. ["FF"].
         * Null means reset this parameter to its factory default.
         */
        private List<String> value;
    }

    /** One association group the device supports. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Getter @Setter @NoArgsConstructor
    public static class AssociationGroup {
        private String  label;
        private int     maxNodes;
        private boolean isLifeline;
        private boolean multiChannel = true;
    }

    /** Descriptive metadata for one configuration parameter (UI / validation use). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Getter @Setter @NoArgsConstructor
    public static class ParamInfo {
        @JsonProperty("#")
        private String  number;
        private String  label;
        private String  description;
        private int     valueSize;
        private Integer minValue;
        private Integer maxValue;
        private Integer defaultValue;
        private String  unit;
        private boolean readOnly;
        private boolean writeOnly;
        private List<Map<String, Object>> options = List.of();
    }

    /** Operational instructions for end users / apps. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Getter @Setter @NoArgsConstructor
    public static class Metadata {
        private String wakeup;
        private String inclusion;
        private String exclusion;
        private String reset;
        private String manual;
    }

    /** A device-specific initialization task to run after configuration. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Getter @Setter @NoArgsConstructor
    public static class InitTask {
        /** Function name, e.g. "poll-pincodes", "config-time". JSON key: "fn". */
        @JsonProperty("fn")
        private String function;

        /** Arbitrary parameters for the function, e.g. {start:1, end:15}. */
        private Map<String, Object> params;
    }
}

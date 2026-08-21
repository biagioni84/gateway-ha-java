package uy.plomo.gateway.descriptor;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * Device descriptor for Zigbee devices.
 *
 * Matching: manufacturer + modelId (both must be present and match).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Getter @Setter @NoArgsConstructor
public class ZigbeeDescriptor extends DeviceDescriptor {

    /** Cluster attributes to read during the interview phase. */
    private List<InterviewStep> interview = List.of();

    /**
     * Cluster bindings to configure on the device so it sends reports
     * to the coordinator.
     */
    private List<Binding> bindings = List.of();

    /** Attribute reporting configurations to push to the device. */
    private List<ReportingConfig> reporting = List.of();

    /** Device-specific initialization tasks to run after setup completes. */
    private List<InitTask> init = List.of();

    @Override
    public boolean matches(Map<String, String> criteria) {
        if (criteria.containsKey("manufacturer") && criteria.containsKey("modelId")) {
            return criteria.get("manufacturer").equalsIgnoreCase(manufacturer)
                    && criteria.get("modelId").equalsIgnoreCase(modelId);
        }
        return false;
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    /** One cluster + attribute list to read during the interview phase. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Getter @Setter @NoArgsConstructor
    public static class InterviewStep {
        /** Cluster name, e.g. "DoorLock", "PowerConfiguration". */
        private String cluster;

        /** Attribute names to read, e.g. ["LockState", "BatteryPercentageRemaining"]. */
        private List<String> attributes = List.of();
    }

    /** A ZDO binding entry to configure on the device. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Getter @Setter @NoArgsConstructor
    public static class Binding {
        /** Source endpoint (hex string), e.g. "01". */
        private String sep;

        /** Destination endpoint (hex string), e.g. "01". */
        private String dep;

        /** Cluster ID (hex string), e.g. "0x0101". */
        private String cluster;
    }

    /** A device-specific initialization task to run after setup completes. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Getter @Setter @NoArgsConstructor
    public static class InitTask {
        /** Function name, e.g. "poll-pincodes". JSON key: "fn". */
        @JsonProperty("fn")
        private String function;

        /** Arbitrary parameters, e.g. {"start": 1, "end": 10}. */
        private Map<String, Object> params;
    }

    /** ZCL attribute reporting configuration to push to the device. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Getter @Setter @NoArgsConstructor
    public static class ReportingConfig {
        /** Source endpoint (hex string). */
        private String sep;

        /** Destination endpoint (hex string). */
        private String dep;

        /** Cluster name, e.g. "DoorLock". */
        private String cluster;

        /** Attribute name, e.g. "LockState". */
        private String attribute;

        /** Minimum reporting interval in seconds. */
        private int minTime;

        /** Maximum reporting interval in seconds. */
        private int maxTime;

        /** Reportable change threshold (hex string), e.g. "01". */
        private String delta;
    }
}

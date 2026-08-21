package uy.plomo.gateway.matter;

import java.util.Map;

/**
 * Matter cluster and attribute name lookup tables.
 *
 * Used for two purposes:
 *   1. Human-readable names in telemetry events (cluster/attribute fields).
 *   2. Matching fwdEvents entries against incoming attribute_updated notifications.
 *
 * fwdEvents entry format: "ClusterName/AttributeName" or "ClusterName/*"
 *   "DoorLock/*"        → forward any attribute update from the DoorLock cluster
 *   "OnOff/OnOff"       → forward only the OnOff attribute of the OnOff cluster
 */
public final class MatterProtocol {

    private MatterProtocol() {}

    // ── Cluster names ─────────────────────────────────────────────────────────

    /** Matter cluster ID → human-readable name. */
    private static final Map<Integer, String> CLUSTER_NAMES = Map.ofEntries(
        Map.entry(3,    "Identify"),
        Map.entry(4,    "Groups"),
        Map.entry(6,    "OnOff"),
        Map.entry(8,    "LevelControl"),
        Map.entry(29,   "Descriptor"),
        Map.entry(40,   "BasicInformation"),
        Map.entry(49,   "NetworkCommissioning"),
        Map.entry(57,   "EthernetNetworkDiagnostics"),
        Map.entry(60,   "AdministratorCommissioning"),
        Map.entry(62,   "OperationalCredentials"),
        Map.entry(63,   "GroupKeyManagement"),
        Map.entry(69,   "BooleanState"),
        Map.entry(257,  "DoorLock"),
        Map.entry(259,  "WindowCovering"),
        Map.entry(513,  "Thermostat"),
        Map.entry(516,  "FanControl"),
        Map.entry(768,  "ColorControl"),
        Map.entry(1024, "IlluminanceMeasurement"),
        Map.entry(1026, "TemperatureMeasurement"),
        Map.entry(1027, "PressureMeasurement"),
        Map.entry(1028, "RelativeHumidityMeasurement"),
        Map.entry(1029, "OccupancySensing"),
        Map.entry(1030, "LeafWetnessMeasurement"),
        Map.entry(1037, "AirQuality")
    );

    // ── Attribute names ───────────────────────────────────────────────────────

    /** Matter cluster ID → (attribute ID → name). Covers commonly forwarded attributes. */
    private static final Map<Integer, Map<Integer, String>> ATTRIBUTE_NAMES = Map.ofEntries(
        Map.entry(6,    Map.of(
            0, "OnOff"
        )),
        Map.entry(8,    Map.of(
            0, "CurrentLevel",
            2, "MinLevel",
            3, "MaxLevel"
        )),
        Map.entry(40,   Map.of(
            1,  "VendorName",
            2,  "VendorID",
            3,  "ProductName",
            4,  "ProductID",
            5,  "NodeLabel",
            14, "UniqueID"
        )),
        Map.entry(69,   Map.of(
            0, "StateValue"
        )),
        Map.entry(257,  Map.of(
            0, "LockState",
            1, "LockType",
            2, "ActuatorEnabled",
            3, "DoorState"
        )),
        Map.entry(513,  Map.of(
            0,  "LocalTemperature",
            17, "OccupiedCoolingSetpoint",
            18, "OccupiedHeatingSetpoint",
            28, "SystemMode"
        )),
        Map.entry(1024, Map.of(
            0, "MeasuredValue"
        )),
        Map.entry(1026, Map.of(
            0, "MeasuredValue",
            1, "MinMeasuredValue",
            2, "MaxMeasuredValue"
        )),
        Map.entry(1027, Map.of(
            0, "MeasuredValue"
        )),
        Map.entry(1028, Map.of(
            0, "MeasuredValue"
        )),
        Map.entry(1029, Map.of(
            0, "Occupancy"
        ))
    );

    // ── Public API ────────────────────────────────────────────────────────────

    /** Returns the cluster name for the given cluster ID, or "Cluster#<id>" if unknown. */
    public static String clusterName(int clusterId) {
        return CLUSTER_NAMES.getOrDefault(clusterId, "Cluster#" + clusterId);
    }

    /**
     * Returns the attribute name for the given cluster/attribute ID pair,
     * or "Attr#<id>" if unknown.
     */
    public static String attributeName(int clusterId, int attributeId) {
        Map<Integer, String> attrs = ATTRIBUTE_NAMES.get(clusterId);
        if (attrs == null) return "Attr#" + attributeId;
        return attrs.getOrDefault(attributeId, "Attr#" + attributeId);
    }

    /**
     * Returns whether the given fwdEvent entry matches an incoming attribute update.
     *
     * fwdEvent format:
     *   "ClusterName/*"           — match any attribute in that cluster
     *   "ClusterName/AttributeName" — match only that specific attribute
     *
     * @param fwdEvent    entry from Device.fwdEvents, e.g. "DoorLock/*" or "OnOff/OnOff"
     * @param clusterId   cluster ID from the attribute_updated event
     * @param attributeId attribute ID from the attribute_updated event
     */
    public static boolean matchesFwdEvent(String fwdEvent, int clusterId, int attributeId) {
        if (fwdEvent == null) return false;
        int slash = fwdEvent.indexOf('/');
        if (slash < 0) return false;

        String eventCluster = fwdEvent.substring(0, slash);
        String eventAttr    = fwdEvent.substring(slash + 1);

        if (!eventCluster.equals(clusterName(clusterId))) return false;
        return "*".equals(eventAttr) || eventAttr.equals(attributeName(clusterId, attributeId));
    }
}

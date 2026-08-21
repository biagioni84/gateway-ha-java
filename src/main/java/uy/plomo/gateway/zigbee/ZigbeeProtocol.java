package uy.plomo.gateway.zigbee;

import java.util.*;

/**
 * Static tables mapping Zigbee cluster IDs and command IDs to names,
 * and helpers for encoding/decoding payloads of the commands used in this gateway.
 *
 * Only the clusters/commands actually needed are included.
 * Mirrors relevant parts of the legacy Clojure implementation.
 */
public final class ZigbeeProtocol {

    // ── ZCL type codes ────────────────────────────────────────────────────────

    public static final int ZCL_BOOLEAN    = 0x10;
    public static final int ZCL_BITMAP8    = 0x18;
    public static final int ZCL_BITMAP16   = 0x19;
    public static final int ZCL_UINT8      = 0x20;
    public static final int ZCL_UINT16     = 0x21;
    public static final int ZCL_UINT24     = 0x22;
    public static final int ZCL_UINT32     = 0x23;
    public static final int ZCL_INT8       = 0x28;
    public static final int ZCL_INT16      = 0x29;
    public static final int ZCL_ENUM8      = 0x30;
    public static final int ZCL_CHARSTRING = 0x42;

    // ── Cluster attribute spec ────────────────────────────────────────────────

    /**
     * Specifies how to address and decode a ZCL attribute.
     * @param clusterId  4-digit cluster hex without prefix, e.g. "0000"
     * @param attrId     attribute ID as integer
     * @param typeId     ZCL data type code, e.g. {@link #ZCL_CHARSTRING}
     */
    public record ClusterAttr(String clusterId, int attrId, int typeId) {}

    /** clusterName → attrName → ClusterAttr. */
    private static final Map<String, Map<String, ClusterAttr>> ATTRIBUTES;

    static {
        Map<String, Map<String, ClusterAttr>> m = new LinkedHashMap<>();

        Map<String, ClusterAttr> basic = new LinkedHashMap<>();
        basic.put("ZCLVersion",       new ClusterAttr("0000", 0x0000, ZCL_UINT8));
        basic.put("ManufacturerName", new ClusterAttr("0000", 0x0004, ZCL_CHARSTRING));
        basic.put("ModelIdentifier",  new ClusterAttr("0000", 0x0005, ZCL_CHARSTRING));
        basic.put("PowerSource",      new ClusterAttr("0000", 0x0007, ZCL_ENUM8));
        basic.put("SWBuildID",        new ClusterAttr("0000", 0x4000, ZCL_CHARSTRING));
        m.put("Basic", Collections.unmodifiableMap(basic));

        Map<String, ClusterAttr> powerCfg = new LinkedHashMap<>();
        powerCfg.put("BatteryVoltage",             new ClusterAttr("0001", 0x0020, ZCL_UINT8));
        powerCfg.put("BatteryPercentageRemaining",  new ClusterAttr("0001", 0x0021, ZCL_UINT8));
        m.put("PowerConfiguration", Collections.unmodifiableMap(powerCfg));

        Map<String, ClusterAttr> onoff = new LinkedHashMap<>();
        onoff.put("OnOff", new ClusterAttr("0006", 0x0000, ZCL_BOOLEAN));
        m.put("OnOff", Collections.unmodifiableMap(onoff));

        Map<String, ClusterAttr> doorlock = new LinkedHashMap<>();
        doorlock.put("LockState",                          new ClusterAttr("0101", 0x0000, ZCL_ENUM8));
        doorlock.put("LockType",                           new ClusterAttr("0101", 0x0001, ZCL_ENUM8));
        doorlock.put("ActuatorEnabled",                    new ClusterAttr("0101", 0x0002, ZCL_BOOLEAN));
        doorlock.put("DoorState",                          new ClusterAttr("0101", 0x0003, ZCL_ENUM8));
        doorlock.put("NumberOfPINUsersSupported",           new ClusterAttr("0101", 0x0012, ZCL_UINT16));
        doorlock.put("NumberOfWeekDaySchedulesSupported",   new ClusterAttr("0101", 0x0014, ZCL_UINT8));
        doorlock.put("NumberOfYearDaySchedulesSupported",   new ClusterAttr("0101", 0x0015, ZCL_UINT8));
        doorlock.put("MaxPINCodeLength",                   new ClusterAttr("0101", 0x0017, ZCL_UINT8));
        doorlock.put("MinPINCodeLength",                   new ClusterAttr("0101", 0x0018, ZCL_UINT8));
        m.put("DoorLock", Collections.unmodifiableMap(doorlock));

        Map<String, ClusterAttr> thermostat = new LinkedHashMap<>();
        thermostat.put("LocalTemperature",        new ClusterAttr("0201", 0x0000, ZCL_INT16));
        thermostat.put("OccupiedCoolingSetpoint", new ClusterAttr("0201", 0x0011, ZCL_INT16));
        thermostat.put("OccupiedHeatingSetpoint", new ClusterAttr("0201", 0x0012, ZCL_INT16));
        thermostat.put("SystemMode",              new ClusterAttr("0201", 0x001C, ZCL_ENUM8));
        m.put("Thermostat", Collections.unmodifiableMap(thermostat));

        ATTRIBUTES = Collections.unmodifiableMap(m);
    }

    // ── Cluster IDs ───────────────────────────────────────────────────────────

    public static final String CLUSTER_BASIC     = "0000";
    public static final String CLUSTER_ONOFF     = "0006";
    public static final String CLUSTER_DOORLOCK  = "0101";

    /** Map of cluster hex ID (4 hex digits, no prefix) → cluster name. */
    private static final Map<String, String> CLUSTER_NAMES = Map.of(
            "0000", "Basic",
            "0006", "OnOff",
            "0101", "DoorLock",
            "0201", "Thermostat",
            "0202", "FanControl",
            "0402", "TemperatureMeasurement",
            "0001", "PowerConfiguration"
    );

    /**
     * Command names for DoorLock cluster (0x0101).
     * Key = command hex (2 hex digits) + ":" + direction ("client"=from device / "server"=to device).
     * We store client-direction (incoming, from device) since that's what we match on.
     */
    private static final Map<String, String> DOORLOCK_CLIENT_CMDS = new LinkedHashMap<>();
    private static final Map<String, String> DOORLOCK_SERVER_CMDS = new LinkedHashMap<>();

    static {
        // Client-side commands (reports FROM device, direction = client in ZCL sense)
        DOORLOCK_CLIENT_CMDS.put("00", "LockDoorResponse");
        DOORLOCK_CLIENT_CMDS.put("01", "UnlockDoorResponse");
        DOORLOCK_CLIENT_CMDS.put("04", "GetLogRecordResponse");
        DOORLOCK_CLIENT_CMDS.put("05", "SetPINCodeResponse");
        DOORLOCK_CLIENT_CMDS.put("06", "GetPINCodeResponse");
        DOORLOCK_CLIENT_CMDS.put("08", "ClearPINCodeResponse");
        DOORLOCK_CLIENT_CMDS.put("0b", "SetWeekdayScheduleResponse");
        DOORLOCK_CLIENT_CMDS.put("0c", "GetWeekdayScheduleResponse");
        DOORLOCK_CLIENT_CMDS.put("0d", "ClearWeekdayScheduleResponse");
        DOORLOCK_CLIENT_CMDS.put("0e", "SetYearDayScheduleResponse");
        DOORLOCK_CLIENT_CMDS.put("0f", "GetYearDayScheduleResponse");
        DOORLOCK_CLIENT_CMDS.put("20", "OperationEventNotification");
        DOORLOCK_CLIENT_CMDS.put("21", "ProgrammingEventNotification");
        DOORLOCK_CLIENT_CMDS.put("0a", "ReportAttributes");

        // Server-side commands (sent TO device)
        DOORLOCK_SERVER_CMDS.put("00", "LockDoor");
        DOORLOCK_SERVER_CMDS.put("01", "UnlockDoor");
        DOORLOCK_SERVER_CMDS.put("04", "GetLogRecord");
        DOORLOCK_SERVER_CMDS.put("05", "SetPINCode");
        DOORLOCK_SERVER_CMDS.put("06", "GetPINCode");
        DOORLOCK_SERVER_CMDS.put("07", "ClearPINCode");
        DOORLOCK_SERVER_CMDS.put("0b", "SetWeekdaySchedule");
        DOORLOCK_SERVER_CMDS.put("0c", "GetWeekdaySchedule");
        DOORLOCK_SERVER_CMDS.put("0d", "ClearWeekdaySchedule");
        DOORLOCK_SERVER_CMDS.put("0e", "SetYearDaySchedule");
        DOORLOCK_SERVER_CMDS.put("0f", "GetYearDaySchedule");
        DOORLOCK_SERVER_CMDS.put("10", "ClearYearDaySchedule");
    }

    private static final Map<String, String> ONOFF_CLIENT_CMDS = Map.of(
            "0b", "DefaultResponse",
            "0a", "ReportAttributes"
    );

    /** ZCL foundation (global) client-direction commands — apply to all clusters as fallback. */
    private static final Map<String, String> ZCL_GLOBAL_CLIENT_CMDS = Map.of(
            "00", "ReadAttributes",
            "01", "ReadAttributesResponse",
            "04", "WriteAttributes",
            "05", "WriteAttributesUndivided",
            "06", "WriteAttributesResponse",
            "07", "WriteAttributesNoResponse",
            "0a", "ReportAttributes",
            "0b", "DefaultResponse",
            "0c", "DiscoverAttributesResponse"
    );

    // ── Lookup helpers ────────────────────────────────────────────────────────

    public static String clusterName(String hexId) {
        if (hexId == null) return "Unknown";
        String key = hexId.toLowerCase().replace("0x", "");
        // normalize to 4 digits
        while (key.length() < 4) key = "0" + key;
        return CLUSTER_NAMES.getOrDefault(key, "Unknown[" + key + "]");
    }

    /**
     * Returns the command name for an incoming (client-direction) command.
     * @param clusterHex  cluster hex (no prefix, e.g. "0101")
     * @param cmdHex      command hex (no prefix, e.g. "06")
     */
    public static String commandName(String clusterHex, String cmdHex) {
        if (clusterHex == null || cmdHex == null) return cmdHex;
        String cid = clusterHex.toLowerCase().replace("0x", "");
        String cmd = cmdHex.toLowerCase().replace("0x", "");
        while (cid.length() < 4) cid = "0" + cid;
        while (cmd.length() < 2) cmd = "0" + cmd;
        return switch (cid) {
            case "0101" -> DOORLOCK_CLIENT_CMDS.getOrDefault(cmd,
                    ZCL_GLOBAL_CLIENT_CMDS.getOrDefault(cmd, "Unknown[" + cmd + "]"));
            case "0006" -> ONOFF_CLIENT_CMDS.getOrDefault(cmd,
                    ZCL_GLOBAL_CLIENT_CMDS.getOrDefault(cmd, "Unknown[" + cmd + "]"));
            default     -> ZCL_GLOBAL_CLIENT_CMDS.getOrDefault(cmd, "cmd_" + cmd);
        };
    }

    /** Returns the {@link ClusterAttr} spec for a cluster/attribute pair, or null if unknown. */
    public static ClusterAttr lookupAttr(String clusterName, String attrName) {
        Map<String, ClusterAttr> clusterMap = ATTRIBUTES.get(clusterName);
        return clusterMap != null ? clusterMap.get(attrName) : null;
    }

    /** Returns the server-direction command ID for a named DoorLock command. */
    public static String doorlockServerCmdId(String name) {
        return DOORLOCK_SERVER_CMDS.entrySet().stream()
                .filter(e -> e.getValue().equals(name))
                .map(Map.Entry::getKey)
                .findFirst().orElse(null);
    }

    // ── Payload encoding ──────────────────────────────────────────────────────

    /** Encode int as little-endian uint16, returns "XX XX". */
    public static String leUint16(int v) {
        return String.format("%02x %02x", v & 0xFF, (v >> 8) & 0xFF);
    }

    /** Encode int as little-endian uint32, returns "XX XX XX XX". */
    public static String leUint32(long v) {
        return String.format("%02x %02x %02x %02x",
                (v)       & 0xFF,
                (v >> 8)  & 0xFF,
                (v >> 16) & 0xFF,
                (v >> 24) & 0xFF);
    }

    /** Encode ASCII string as ZCL octstring (1-byte length prefix + bytes). */
    public static String octstring(String s) {
        byte[] b = s.getBytes();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%02x", b.length));
        for (byte x : b) sb.append(String.format(" %02x", x));
        return sb.toString();
    }

    // ── Attribute read/decode ─────────────────────────────────────────────────

    /**
     * Decodes a single-attribute ReadAttributesResponse payload.
     * ZCL format: [attrId_lo, attrId_hi, status, typeId, value...]
     *
     * @param payload raw payload bytes as two-hex-char strings
     * @return decoded value (Boolean, Integer, Short, String, etc.) or null on failure/error status
     */
    public static Object decodeReadAttributeValue(List<String> payload) {
        if (payload == null || payload.size() < 3) return null;
        int status = parseHex(payload.get(2));
        if (status != 0x00) return null;      // non-SUCCESS status
        if (payload.size() < 4) return null;
        int typeId = parseHex(payload.get(3));
        return decodeZclValue(typeId, payload.subList(4, payload.size()));
    }

    private static Object decodeZclValue(int typeId, List<String> bytes) {
        if (bytes == null || bytes.isEmpty()) return null;
        return switch (typeId) {
            case ZCL_BOOLEAN            -> parseHex(bytes.get(0)) != 0;
            case ZCL_UINT8, ZCL_ENUM8,
                 ZCL_BITMAP8            -> parseHex(bytes.get(0));
            case ZCL_UINT16, ZCL_BITMAP16 -> bytes.size() >= 2
                    ? parseLeUint16(bytes, 0) : parseHex(bytes.get(0));
            case ZCL_UINT24             -> bytes.size() >= 3
                    ? (parseHex(bytes.get(0)) | (parseHex(bytes.get(1)) << 8) | (parseHex(bytes.get(2)) << 16))
                    : parseHex(bytes.get(0));
            case ZCL_UINT32             -> bytes.size() >= 4
                    ? (int) parseLeUint32(bytes, 0) : parseHex(bytes.get(0));
            case ZCL_INT8               -> (byte)  parseHex(bytes.get(0));
            case ZCL_INT16              -> bytes.size() >= 2
                    ? (short) parseLeUint16(bytes, 0) : (byte) parseHex(bytes.get(0));
            case ZCL_CHARSTRING         -> {
                int len = parseHex(bytes.get(0));
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < len && (1 + i) < bytes.size(); i++) {
                    sb.append((char) parseHex(bytes.get(1 + i)));
                }
                yield sb.toString();
            }
            default -> String.join(" ", bytes); // raw hex fallback
        };
    }

    // ── Payload decoding ──────────────────────────────────────────────────────

    /**
     * Decode the payload of an incoming DoorLock frame.
     * Returns a map of field name → value string.
     */
    public static Map<String, String> decodeDoorLockPayload(String commandName, List<String> payload) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (payload == null || payload.isEmpty()) return fields;

        try {
            switch (commandName) {
                case "GetPINCodeResponse"  -> decodeGetPINCodeResponse(payload, fields);
                case "SetPINCodeResponse"  -> { fields.put("Status", payload.get(0)); }
                case "ClearPINCodeResponse"-> { fields.put("Status", payload.get(0)); }
                case "GetWeekdayScheduleResponse" -> decodeGetWeekdayScheduleResponse(payload, fields);
                case "SetWeekdayScheduleResponse" -> { fields.put("Status", payload.get(0)); }
                case "GetYearDayScheduleResponse" -> decodeGetYearDayScheduleResponse(payload, fields);
                case "SetYearDayScheduleResponse" -> { fields.put("Status", payload.get(0)); }
                case "ProgrammingEventNotification" -> decodeProgrammingEventNotification(payload, fields);
                case "OperationEventNotification"   -> decodeOperationEventNotification(payload, fields);
                case "ReportAttributes"             -> decodeReportAttributes(payload, fields);
                default -> { /* store raw payload as hex */ fields.put("raw", String.join(" ", payload)); }
            }
        } catch (Exception e) {
            fields.put("raw", String.join(" ", payload));
        }
        return fields;
    }

    private static void decodeGetPINCodeResponse(List<String> p, Map<String, String> f) {
        if (p.size() < 4) return;
        int uid = parseLeUint16(p, 0);
        f.put("UserID",     String.valueOf(uid));
        f.put("UserStatus", p.get(2));
        f.put("UserType",   p.get(3));
        // Code: octstring starting at byte 4
        if (p.size() > 4) {
            int len = parseHex(p.get(4));
            StringBuilder code = new StringBuilder();
            for (int i = 0; i < len && (5 + i) < p.size(); i++) {
                code.append((char) parseHex(p.get(5 + i)));
            }
            f.put("Code", code.toString());
        }
    }

    private static void decodeGetWeekdayScheduleResponse(List<String> p, Map<String, String> f) {
        if (p.size() < 3) return;
        f.put("ScheduleID", p.get(0));
        int uid = parseLeUint16(p, 1);
        f.put("UserID", String.valueOf(uid));
        f.put("Status", p.get(3));
        if (p.size() > 4 && "00".equals(p.get(3))) { // SUCCESS
            f.put("DaysMask",    p.get(4));
            f.put("StartHour",   p.size() > 5 ? p.get(5) : "00");
            f.put("StartMinute", p.size() > 6 ? p.get(6) : "00");
            f.put("EndHour",     p.size() > 7 ? p.get(7) : "00");
            f.put("EndMinute",   p.size() > 8 ? p.get(8) : "00");
        }
    }

    private static void decodeGetYearDayScheduleResponse(List<String> p, Map<String, String> f) {
        if (p.size() < 3) return;
        f.put("ScheduleID", p.get(0));
        int uid = parseLeUint16(p, 1);
        f.put("UserID", String.valueOf(uid));
        f.put("Status", p.get(3));
        if (p.size() >= 12 && "00".equals(p.get(3))) { // SUCCESS
            long start = parseLeUint32(p, 4);
            long end   = parseLeUint32(p, 8);
            f.put("LocalStartTime", String.valueOf(start));
            f.put("LocalEndTime",   String.valueOf(end));
        }
    }

    private static void decodeProgrammingEventNotification(List<String> p, Map<String, String> f) {
        if (p.size() < 3) return;
        f.put("EventSource", p.get(0));
        f.put("EventCode",   p.get(1));
        int uid = parseLeUint16(p, 2);
        f.put("UserID", String.valueOf(uid));
        if (p.size() > 4) {
            int codeLen = parseHex(p.get(4));
            StringBuilder code = new StringBuilder();
            for (int i = 0; i < codeLen && (5 + i) < p.size(); i++) {
                code.append((char) parseHex(p.get(5 + i)));
            }
            f.put("Code", code.toString());
            int base = 5 + codeLen;
            if (base < p.size()) f.put("UserType",   p.get(base));
            if (base + 1 < p.size()) f.put("UserStatus", p.get(base + 1));
        }
    }

    private static void decodeOperationEventNotification(List<String> p, Map<String, String> f) {
        if (p.size() < 2) return;
        f.put("OperationEventSource", p.get(0));
        f.put("OperationEventCode",   p.get(1));
        if (p.size() >= 4) {
            int uid = parseLeUint16(p, 2);
            f.put("UserID", String.valueOf(uid));
        }
    }

    private static void decodeReportAttributes(List<String> p, Map<String, String> f) {
        // Minimal: store raw
        if (!p.isEmpty()) f.put("raw", String.join(" ", p));
    }

    // ── Parse helpers ─────────────────────────────────────────────────────────

    private static int parseLeUint16(List<String> p, int offset) {
        int lo = parseHex(p.get(offset));
        int hi = parseHex(p.get(offset + 1));
        return lo | (hi << 8);
    }

    private static long parseLeUint32(List<String> p, int offset) {
        long result = 0;
        for (int i = 0; i < 4; i++) {
            result |= ((long) parseHex(p.get(offset + i))) << (8 * i);
        }
        return result;
    }

    static int parseHex(String s) {
        if (s == null || s.isBlank()) return 0;
        return Integer.parseUnsignedInt(s.trim().replace("0x", "").replace("0X", ""), 16);
    }

    private ZigbeeProtocol() {}
}

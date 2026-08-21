package uy.plomo.gateway.zwave;

import java.util.Map;

/** Static helpers shared across Z-Wave components. */
final class ZWaveUtils {

    private ZWaveUtils() {}

    /** "0x" + fields[key1] + fields[key2], or null if either field is absent. */
    static String buildHexId(Map<String, Object> fields, String key1, String key2) {
        if (fields == null) return null;
        Object v1 = fields.get(key1);
        Object v2 = fields.get(key2);
        if (v1 == null || v2 == null) return null;
        return "0x" + v1.toString() + v2.toString();
    }

    /** Returns fields.get(key).toString(), or null if fields or value is null. */
    static String getString(Map<String, Object> fields, String key) {
        if (fields == null) return null;
        Object v = fields.get(key);
        return v != null ? v.toString() : null;
    }
}

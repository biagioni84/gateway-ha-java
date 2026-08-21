package uy.plomo.gateway.homeassistant;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

/**
 * Maps a Home Assistant entity's domain (+ device_class attribute, where relevant) to the
 * logical device "type" string used in the gateway's device summary format.
 *
 * Domains not covered here return null and are not represented as gateway Devices — they're
 * not addressable/controllable devices (automations, scripts, zones, persons, updates, etc.).
 */
public final class HomeAssistantTypeMapper {

    private HomeAssistantTypeMapper() {}

    private static final Set<String> ON_OFF_ONLY_COLOR_MODE = Set.of("onoff");

    public static String inferType(String domain, JsonNode attributes) {
        if (domain == null) return null;
        return switch (domain) {
            case "lock"          -> "lock";
            case "switch"        -> "switch";
            case "light"         -> isDimmableLight(attributes) ? "dimmer" : "switch";
            case "climate"       -> "thermostat";
            case "camera"        -> "camera";
            case "cover"         -> "cover";
            case "fan"           -> "fan";
            case "binary_sensor" -> binarySensorType(deviceClass(attributes));
            case "sensor"        -> sensorType(deviceClass(attributes));
            default -> null;
        };
    }

    private static boolean isDimmableLight(JsonNode attributes) {
        if (attributes == null) return true; // unknown capability — default to the richer control surface
        JsonNode modes = attributes.path("supported_color_modes");
        if (!modes.isArray() || modes.isEmpty()) return true;
        for (JsonNode m : modes) {
            if (!ON_OFF_ONLY_COLOR_MODE.contains(m.asText(""))) return true;
        }
        return false;
    }

    private static String deviceClass(JsonNode attributes) {
        return attributes != null ? attributes.path("device_class").asText(null) : null;
    }

    private static String binarySensorType(String deviceClass) {
        if (deviceClass == null) return "sensor-binary";
        return switch (deviceClass) {
            case "door", "window", "opening", "garage_door" -> "sensor-contact";
            case "motion", "occupancy", "presence"          -> "sensor-occupancy";
            case "battery"                                  -> "sensor-battery";
            default -> "sensor-binary";
        };
    }

    private static String sensorType(String deviceClass) {
        if (deviceClass == null) return "sensor-generic";
        return switch (deviceClass) {
            case "temperature" -> "sensor-temperature";
            case "humidity"    -> "sensor-humidity";
            case "illuminance" -> "sensor-illuminance";
            case "battery"     -> "sensor-battery";
            default -> "sensor-generic";
        };
    }
}

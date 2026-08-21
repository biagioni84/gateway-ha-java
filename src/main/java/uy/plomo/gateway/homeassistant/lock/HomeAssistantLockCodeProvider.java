package uy.plomo.gateway.homeassistant.lock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uy.plomo.gateway.homeassistant.HomeAssistantEntityRegistry;
import uy.plomo.gateway.homeassistant.HomeAssistantInterface;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Lock control + usercode management routed through Home Assistant.
 *
 * lock()/unlock() work for any HA lock entity via the standard lock.lock/lock.unlock services.
 * Usercode management is integration-specific, resolved live per entity via
 * HomeAssistantEntityRegistry:
 *
 *   - Z-Wave JS: zwave_js.get_lock_usercode / set_lock_usercode / clear_lock_usercode — a
 *     clean, documented, per-lock API. Verified against home-assistant.io's actions docs.
 *
 *   - ZHA: no dedicated usercode service exists (tracked upstream: zigpy/zha#729). Falls back
 *     to zha.issue_zigbee_cluster_command, sending the DoorLock cluster's own
 *     SetPINCode(0x05)/GetPINCode(0x06)/ClearPINCode(0x07) commands directly — the same
 *     commands the old direct-radio ZigbeeController used to send over serial, just issued
 *     through HA instead. Cluster ID and command IDs/params verified against zigpy's own
 *     source (zigpy/zigpy/zcl/clusters/closures.py). set/clear are fire-and-forget and should
 *     be reliable; get's response depends on the lock's ZHA "quirk" correctly relaying the
 *     device's reply — real-world reports show this failing on some lock models.
 *
 *   - Zigbee2MQTT (via HA's generic "mqtt" integration + MQTT discovery): no HA service
 *     either, since Z2M isn't a native HA integration — HA only sees it through MQTT
 *     discovery. Publishes directly to the device's Z2M "set" topic via HA's own mqtt.publish
 *     service, keeping Home Assistant as the gateway's single point of contact rather than
 *     opening a second, separate MQTT connection to the Z2M broker. The payload shape is
 *     Z2M's common lock pin_code convention (seen across several supported models, e.g.
 *     Kwikset 99100-045) — VERIFY against your specific lock's page on zigbee2mqtt.io and
 *     adjust field names if it differs. Reading a code back isn't implemented for this path.
 *
 *   - Anything else: reported as unsupported rather than assumed to work.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class HomeAssistantLockCodeProvider implements LockCodeProvider {

    private static final String ZWAVE_JS_DOMAIN = "zwave_js";
    private static final String ZHA_DOMAIN      = "zha";

    private static final int DOORLOCK_CLUSTER_ID   = 0x0101; // 257 — verified against zigpy's closures.py
    private static final int ZCL_CMD_SET_PIN_CODE   = 0x05;
    private static final int ZCL_CMD_GET_PIN_CODE   = 0x06;
    private static final int ZCL_CMD_CLEAR_PIN_CODE = 0x07;

    private final HomeAssistantInterface      haInterface;
    private final HomeAssistantEntityRegistry entityRegistry;
    private final ObjectMapper                objectMapper;

    @Override
    public Map<String, Object> lock(String entityId, boolean lock) {
        try {
            haInterface.callService("lock", lock ? "lock" : "unlock", entityId, null)
                    .orTimeout(10, TimeUnit.SECONDS).join();
            return Map.of("status", "ok");
        } catch (Exception e) {
            return errorResult("lock", e);
        }
    }

    @Override
    public Map<String, Object> getUserCode(String entityId, int slot) {
        String platform = entityRegistry.resolvePlatform(entityId);
        if (ZWAVE_JS_DOMAIN.equals(platform)) return getUserCodeZwaveJs(entityId, slot);
        if (ZHA_DOMAIN.equals(platform)) return getUserCodeZha(entityId, slot);
        if (entityRegistry.resolveZigbee2MqttFriendlyName(entityId) != null) return getUserCodeZigbee2Mqtt();
        return unsupported(platform);
    }

    @Override
    public Map<String, Object> setUserCode(String entityId, int slot, String code) {
        String platform = entityRegistry.resolvePlatform(entityId);
        if (ZWAVE_JS_DOMAIN.equals(platform)) return setUserCodeZwaveJs(entityId, slot, code);
        if (ZHA_DOMAIN.equals(platform)) return setUserCodeZha(entityId, slot, code);
        String z2mName = entityRegistry.resolveZigbee2MqttFriendlyName(entityId);
        if (z2mName != null) return setUserCodeZigbee2Mqtt(z2mName, slot, code);
        return unsupported(platform);
    }

    @Override
    public Map<String, Object> deleteUserCode(String entityId, int slot) {
        String platform = entityRegistry.resolvePlatform(entityId);
        if (ZWAVE_JS_DOMAIN.equals(platform)) return deleteUserCodeZwaveJs(entityId, slot);
        if (ZHA_DOMAIN.equals(platform)) return deleteUserCodeZha(entityId, slot);
        String z2mName = entityRegistry.resolveZigbee2MqttFriendlyName(entityId);
        if (z2mName != null) return deleteUserCodeZigbee2Mqtt(z2mName, slot);
        return unsupported(platform);
    }

    // ── Z-Wave JS ────────────────────────────────────────────────────────────

    private Map<String, Object> getUserCodeZwaveJs(String entityId, int slot) {
        try {
            JsonNode result = haInterface.callService(ZWAVE_JS_DOMAIN, "get_lock_usercode", entityId,
                            Map.of("code_slot", slot), true)
                    .orTimeout(10, TimeUnit.SECONDS).join();
            JsonNode response = result != null ? result.path("response") : null;
            JsonNode slotData = response != null ? response.path(String.valueOf(slot)) : null;
            if (slotData == null || slotData.isMissingNode()) {
                return Map.of("error", "no usercode data returned for slot " + slot);
            }
            return Map.of(
                    "code",  slotData.path("usercode").asText(""),
                    "inUse", slotData.path("in_use").asBoolean(false));
        } catch (Exception e) {
            return errorResult("get_lock_usercode", e);
        }
    }

    private Map<String, Object> setUserCodeZwaveJs(String entityId, int slot, String code) {
        try {
            haInterface.callService(ZWAVE_JS_DOMAIN, "set_lock_usercode", entityId,
                            Map.of("code_slot", slot, "usercode", code))
                    .orTimeout(10, TimeUnit.SECONDS).join();
            return Map.of("status", "ok");
        } catch (Exception e) {
            return errorResult("set_lock_usercode", e);
        }
    }

    private Map<String, Object> deleteUserCodeZwaveJs(String entityId, int slot) {
        try {
            haInterface.callService(ZWAVE_JS_DOMAIN, "clear_lock_usercode", entityId,
                            Map.of("code_slot", slot))
                    .orTimeout(10, TimeUnit.SECONDS).join();
            return Map.of("status", "ok");
        } catch (Exception e) {
            return errorResult("clear_lock_usercode", e);
        }
    }

    // ── ZHA (raw DoorLock cluster commands) ─────────────────────────────────

    private Map<String, Object> getUserCodeZha(String entityId, int slot) {
        String ieee = entityRegistry.resolveZhaIeee(entityId);
        if (ieee == null) return Map.of("error", "could not resolve the ZHA device's IEEE address for " + entityId);
        try {
            JsonNode result = issueZhaCommand(ieee, ZCL_CMD_GET_PIN_CODE, Map.of("user_id", slot));
            JsonNode response = result != null ? result.path("response") : null;
            if (response == null || response.isMissingNode() || response.isNull()) {
                return Map.of("error", "the lock did not return a readable PIN code response for slot " + slot
                        + " — get_pin_code is known to be unreliable on some ZHA lock models (depends on "
                        + "the device's ZHA \"quirk\"); check the lock's device page in Home Assistant directly");
            }
            // Shape not confirmed against a real device response — returned as-is rather than
            // guessing field names the way the Z-Wave JS path's {code, inUse} is confirmed.
            return Map.of("raw", response.toString());
        } catch (Exception e) {
            return errorResult("zha get_pin_code", e);
        }
    }

    private Map<String, Object> setUserCodeZha(String entityId, int slot, String code) {
        String ieee = entityRegistry.resolveZhaIeee(entityId);
        if (ieee == null) return Map.of("error", "could not resolve the ZHA device's IEEE address for " + entityId);
        Map<String, Object> params = Map.of(
                "user_id", slot,
                "user_status", 1, // ZCL UserStatus: 1 = occupied/enabled
                "user_type", 0,   // ZCL UserType: 0 = unrestricted
                "pin_code", code);
        try {
            issueZhaCommand(ieee, ZCL_CMD_SET_PIN_CODE, params);
            return Map.of("status", "ok");
        } catch (Exception e) {
            return errorResult("zha set_pin_code", e);
        }
    }

    private Map<String, Object> deleteUserCodeZha(String entityId, int slot) {
        String ieee = entityRegistry.resolveZhaIeee(entityId);
        if (ieee == null) return Map.of("error", "could not resolve the ZHA device's IEEE address for " + entityId);
        try {
            issueZhaCommand(ieee, ZCL_CMD_CLEAR_PIN_CODE, Map.of("user_id", slot));
            return Map.of("status", "ok");
        } catch (Exception e) {
            return errorResult("zha clear_pin_code", e);
        }
    }

    private JsonNode issueZhaCommand(String ieee, int command, Map<String, Object> params) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ieee", ieee);
        data.put("endpoint_id", 1); // the DoorLock cluster is virtually always on endpoint 1 for Zigbee locks
        data.put("cluster_id", DOORLOCK_CLUSTER_ID);
        data.put("cluster_type", "in");
        data.put("command", command);
        data.put("command_type", "server");
        data.put("params", params);
        return haInterface.callService(ZHA_DOMAIN, "issue_zigbee_cluster_command", null, data, true)
                .orTimeout(10, TimeUnit.SECONDS).join();
    }

    // ── Zigbee2MQTT (via HA's mqtt.publish, no dedicated HA integration) ─────

    private Map<String, Object> getUserCodeZigbee2Mqtt() {
        return Map.of("error", "reading a PIN code back from a Zigbee2MQTT lock is not implemented here "
                + "— check the lock's state/attributes in Home Assistant, or Zigbee2MQTT's own UI");
    }

    private Map<String, Object> setUserCodeZigbee2Mqtt(String friendlyName, int slot, String code) {
        Map<String, Object> pinCode = new LinkedHashMap<>();
        pinCode.put("user", slot);
        pinCode.put("user_type", "unrestricted");
        pinCode.put("user_enabled", true);
        pinCode.put("pin_code", code);
        return publishZigbee2MqttSet(friendlyName, Map.of("pin_code", pinCode));
    }

    private Map<String, Object> deleteUserCodeZigbee2Mqtt(String friendlyName, int slot) {
        Map<String, Object> pinCode = new LinkedHashMap<>();
        pinCode.put("user", slot);
        pinCode.put("user_enabled", false);
        return publishZigbee2MqttSet(friendlyName, Map.of("pin_code", pinCode));
    }

    private Map<String, Object> publishZigbee2MqttSet(String friendlyName, Map<String, Object> payload) {
        try {
            String topic = "zigbee2mqtt/" + friendlyName + "/set";
            String json = objectMapper.writeValueAsString(payload);
            haInterface.callService("mqtt", "publish", null, Map.of("topic", topic, "payload", json))
                    .orTimeout(10, TimeUnit.SECONDS).join();
            return Map.of("status", "ok");
        } catch (Exception e) {
            return errorResult("mqtt.publish (zigbee2mqtt)", e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Map<String, Object> unsupported(String platform) {
        return Map.of("error", "pincode management is not supported for this lock's integration"
                + (platform != null ? " (" + platform + ")" : ""));
    }

    private static Map<String, Object> errorResult(String op, Exception e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        log.warn("Home Assistant: {} failed: {}", op, cause.getMessage());
        return Map.of("error", cause.getMessage() != null ? cause.getMessage() : op + " failed");
    }
}

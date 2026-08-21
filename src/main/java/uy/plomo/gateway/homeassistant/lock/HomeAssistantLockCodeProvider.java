package uy.plomo.gateway.homeassistant.lock;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uy.plomo.gateway.homeassistant.HomeAssistantEntityRegistry;
import uy.plomo.gateway.homeassistant.HomeAssistantInterface;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Lock control + usercode management routed through Home Assistant.
 *
 * lock()/unlock() work for any HA lock entity via the standard lock.lock/lock.unlock services.
 * Usercode management is integration-specific:
 *   - Z-Wave JS: zwave_js.get_lock_usercode / set_lock_usercode / clear_lock_usercode — verified
 *     against home-assistant.io's actions docs (2026-08). get_lock_usercode needs
 *     return_response — its data comes back under result.response.
 *   - ZHA: no usercode service exists as of this writing (tracked upstream: zigpy/zha#729,
 *     still open per HA 2026.4 release notes) — surfaced as an explicit error rather than a
 *     silent no-op or a guessed call.
 *   - Anything else: reported as unsupported rather than assumed to work.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class HomeAssistantLockCodeProvider implements LockCodeProvider {

    private static final String ZWAVE_JS_DOMAIN = "zwave_js";
    private static final String ZHA_DOMAIN      = "zha";

    private final HomeAssistantInterface     haInterface;
    private final HomeAssistantEntityRegistry entityRegistry;

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
        if (!ZWAVE_JS_DOMAIN.equals(platform)) return unsupported(platform);
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

    @Override
    public Map<String, Object> setUserCode(String entityId, int slot, String code) {
        String platform = entityRegistry.resolvePlatform(entityId);
        if (!ZWAVE_JS_DOMAIN.equals(platform)) return unsupported(platform);
        try {
            haInterface.callService(ZWAVE_JS_DOMAIN, "set_lock_usercode", entityId,
                            Map.of("code_slot", slot, "usercode", code))
                    .orTimeout(10, TimeUnit.SECONDS).join();
            return Map.of("status", "ok");
        } catch (Exception e) {
            return errorResult("set_lock_usercode", e);
        }
    }

    @Override
    public Map<String, Object> deleteUserCode(String entityId, int slot) {
        String platform = entityRegistry.resolvePlatform(entityId);
        if (!ZWAVE_JS_DOMAIN.equals(platform)) return unsupported(platform);
        try {
            haInterface.callService(ZWAVE_JS_DOMAIN, "clear_lock_usercode", entityId,
                            Map.of("code_slot", slot))
                    .orTimeout(10, TimeUnit.SECONDS).join();
            return Map.of("status", "ok");
        } catch (Exception e) {
            return errorResult("clear_lock_usercode", e);
        }
    }

    private static Map<String, Object> unsupported(String platform) {
        if (ZHA_DOMAIN.equals(platform)) {
            return Map.of("error", "ZHA does not yet expose Zigbee lock user-code management "
                    + "(tracked upstream: zigpy/zha#729) — manage codes from the lock's own keypad/app for now");
        }
        return Map.of("error", "pincode management is not supported for this lock's integration"
                + (platform != null ? " (" + platform + ")" : ""));
    }

    private static Map<String, Object> errorResult(String op, Exception e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        log.warn("Home Assistant: {} failed: {}", op, cause.getMessage());
        return Map.of("error", cause.getMessage() != null ? cause.getMessage() : op + " failed");
    }
}

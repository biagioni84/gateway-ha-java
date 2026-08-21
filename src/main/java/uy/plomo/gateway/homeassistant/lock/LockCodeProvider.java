package uy.plomo.gateway.homeassistant.lock;

import java.util.Map;

/**
 * Seam for lock control and PIN-code (usercode) management.
 *
 * HomeAssistantLockCodeProvider is the only implementation today, routing through whichever
 * HA integration owns the entity. This interface exists so a future direct-to-lock-radio
 * fallback — for gaps HA's integrations don't cover (e.g. ZHA has no usercode service as of
 * this writing) — can be added or composed later without changing GatewayApiService or
 * HomeAssistantController.
 */
public interface LockCodeProvider {

    Map<String, Object> lock(String entityId, boolean lock);

    Map<String, Object> getUserCode(String entityId, int slot);

    Map<String, Object> setUserCode(String entityId, int slot, String code);

    Map<String, Object> deleteUserCode(String entityId, int slot);
}

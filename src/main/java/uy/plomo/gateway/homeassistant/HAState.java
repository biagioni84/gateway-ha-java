package uy.plomo.gateway.homeassistant;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Live snapshot of a Home Assistant entity's state, as returned by get_states
 * or carried in a state_changed event's old_state/new_state.
 */
public record HAState(String entityId, String state, JsonNode attributes, String lastChanged, String lastUpdated) {

    /** The part of the entity_id before the first dot, e.g. "lock" for "lock.front_door". */
    public String domain() {
        int dot = entityId != null ? entityId.indexOf('.') : -1;
        return dot > 0 ? entityId.substring(0, dot) : "";
    }
}

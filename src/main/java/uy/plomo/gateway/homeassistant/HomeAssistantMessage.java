package uy.plomo.gateway.homeassistant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Represents any inbound message on Home Assistant's WebSocket API.
 *
 *   Auth handshake:     {"type": "auth_required", "ha_version": "..."}
 *                        {"type": "auth_ok", "ha_version": "..."} / {"type": "auth_invalid", "message": "..."}
 *   Command response:   {"id": N, "type": "result", "success": bool, "result": {...}, "error": {...}}
 *   Subscribed event:   {"id": N, "type": "event", "event": {"event_type": "state_changed", "data": {...}}}
 *
 * Discriminate via the `type` field.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class HomeAssistantMessage {

    @JsonProperty("type")
    private String type;

    @JsonProperty("id")
    private Long id;

    @JsonProperty("success")
    private Boolean success;

    @JsonProperty("result")
    private JsonNode result;

    @JsonProperty("error")
    private JsonNode error;

    @JsonProperty("event")
    private JsonNode event;

    @JsonProperty("message")
    private String message;

    public String   getType()    { return type; }
    public Long     getId()      { return id; }
    public Boolean  getSuccess() { return success; }
    public JsonNode getResult()  { return result; }
    public JsonNode getError()   { return error; }
    public JsonNode getEvent()   { return event; }
    public String   getMessage() { return message; }

    public boolean isAuthRequired() { return "auth_required".equals(type); }
    public boolean isAuthOk()       { return "auth_ok".equals(type); }
    public boolean isAuthInvalid()  { return "auth_invalid".equals(type); }
    public boolean isResult()       { return "result".equals(type); }
    public boolean isEvent()        { return "event".equals(type); }

    @Override
    public String toString() {
        return "HomeAssistantMessage{type=" + type + ", id=" + id + "}";
    }
}

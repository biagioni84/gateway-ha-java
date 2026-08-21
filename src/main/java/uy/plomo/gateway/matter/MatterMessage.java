package uy.plomo.gateway.matter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Represents both inbound message types from python-matter-server.
 *
 *   Command response:  { "message_id": "...", "result": {...}, "error_code": N }
 *   Spontaneous event: { "event": "attribute_updated", "data": {...} }
 *
 * Discriminate via: messageId != null → response, event != null → event.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MatterMessage {

    @JsonProperty("message_id")
    private String messageId;

    @JsonProperty("event")
    private String event;

    @JsonProperty("result")
    private JsonNode result;

    @JsonProperty("error_code")
    private Integer errorCode;

    @JsonProperty("data")
    private JsonNode data;

    public String   getMessageId() { return messageId; }
    public String   getEvent()     { return event; }
    public JsonNode getResult()    { return result; }
    public Integer  getErrorCode() { return errorCode; }
    public JsonNode getData()      { return data; }

    public boolean isResponse() { return messageId != null; }
    public boolean isEvent()    { return event != null && messageId == null; }

    @Override
    public String toString() {
        return isResponse()
                ? "MatterMessage{response id=" + messageId + ", error=" + errorCode + "}"
                : "MatterMessage{event=" + event + "}";
    }
}

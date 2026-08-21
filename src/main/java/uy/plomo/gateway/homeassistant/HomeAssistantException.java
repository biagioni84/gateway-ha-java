package uy.plomo.gateway.homeassistant;

import com.fasterxml.jackson.databind.JsonNode;

/** Thrown when Home Assistant's WebSocket API returns {success:false, error:{code,message}}. */
public class HomeAssistantException extends RuntimeException {

    private final String code;

    public HomeAssistantException(String code, String message) {
        super((code != null ? code : "unknown_error") + (message != null && !message.isBlank() ? ": " + message : ""));
        this.code = code;
    }

    public String getCode() { return code; }

    public static HomeAssistantException from(JsonNode error) {
        if (error == null || error.isMissingNode() || error.isNull()) {
            return new HomeAssistantException(null, null);
        }
        return new HomeAssistantException(error.path("code").asText(null), error.path("message").asText(null));
    }
}

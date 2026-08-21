package uy.plomo.gateway.matter;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Thrown when python-matter-server returns a non-zero error_code.
 *
 * python-matter-server ErrorCode enum (matter_server/common/errors.py):
 *   0 OK  1 UNKNOWN_ERROR  2 INVALID_ARGUMENT  3 NOT_FOUND
 *   4 NODE_NOT_INTERVIEWED  5 MATTER_ERROR  6 SDK_ERROR
 *   7 INVALID_STATE  8 NOT_CONNECTED  9 TIMEOUT
 */
public class MatterException extends RuntimeException {

    private static final String[] ERROR_NAMES = {
        "OK", "UNKNOWN_ERROR", "INVALID_ARGUMENT", "NOT_FOUND",
        "NODE_NOT_INTERVIEWED", "MATTER_ERROR", "SDK_ERROR",
        "INVALID_STATE", "NOT_CONNECTED", "TIMEOUT"
    };

    private final int      errorCode;
    private final JsonNode detail;

    public MatterException(int errorCode, JsonNode detail) {
        super(codeName(errorCode) + (detail != null && !detail.isNull() && !detail.asText("").isBlank()
                ? ": " + detail : ""));
        this.errorCode = errorCode;
        this.detail    = detail;
    }

    public int      getErrorCode() { return errorCode; }
    public JsonNode getDetail()    { return detail; }

    private static String codeName(int code) {
        return (code >= 0 && code < ERROR_NAMES.length) ? ERROR_NAMES[code] : "MATTER_ERROR_" + code;
    }
}

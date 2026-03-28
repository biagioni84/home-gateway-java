package uy.plomo.gateway.matter;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Thrown when python-matter-server returns a non-zero error_code.
 */
public class MatterException extends RuntimeException {

    private final int      errorCode;
    private final JsonNode detail;

    public MatterException(int errorCode, JsonNode detail) {
        super("Matter error " + errorCode + ": " + detail);
        this.errorCode = errorCode;
        this.detail    = detail;
    }

    public int      getErrorCode() { return errorCode; }
    public JsonNode getDetail()    { return detail; }
}

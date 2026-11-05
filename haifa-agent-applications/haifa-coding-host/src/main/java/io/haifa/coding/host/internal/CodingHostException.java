package io.haifa.coding.host.internal;

/** Stable JSON-RPC error projection for the local CA Host. */
public final class CodingHostException extends RuntimeException {
    private final int jsonRpcCode;
    private final String reasonCode;

    public CodingHostException(int jsonRpcCode, String reasonCode, String message) {
        super(message);
        this.jsonRpcCode = jsonRpcCode;
        this.reasonCode = reasonCode;
    }

    public CodingHostException(int jsonRpcCode, String reasonCode, String message, Throwable cause) {
        super(message, cause);
        this.jsonRpcCode = jsonRpcCode;
        this.reasonCode = reasonCode;
    }

    public int jsonRpcCode() {
        return jsonRpcCode;
    }

    public String reasonCode() {
        return reasonCode;
    }

    public static CodingHostException invalidParams(String message) {
        return new CodingHostException(-32602, "INVALID_PARAMS", message);
    }

    public static CodingHostException unknownMethod(String method) {
        return new CodingHostException(-32601, "REMOTE_METHOD_UNSUPPORTED", "Unknown method: " + method);
    }

    public static CodingHostException unsupported(String message) {
        return new CodingHostException(-32601, "REMOTE_METHOD_UNSUPPORTED", message);
    }

    public static CodingHostException conflict(String reasonCode, String message) {
        return new CodingHostException(-32009, reasonCode, message);
    }

    public static CodingHostException notFound(String reasonCode, String message) {
        return new CodingHostException(-32004, reasonCode, message);
    }

    public static CodingHostException internal(String message) {
        return new CodingHostException(-32603, "INTERNAL_ERROR", message);
    }
}

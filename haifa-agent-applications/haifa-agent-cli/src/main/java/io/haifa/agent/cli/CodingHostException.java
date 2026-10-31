package io.haifa.agent.cli;

/** Stable JSON-RPC error projection for the local CA Host. */
final class CodingHostException extends RuntimeException {
    private final int jsonRpcCode;
    private final String reasonCode;

    CodingHostException(int jsonRpcCode, String reasonCode, String message) {
        super(message);
        this.jsonRpcCode = jsonRpcCode;
        this.reasonCode = reasonCode;
    }

    CodingHostException(int jsonRpcCode, String reasonCode, String message, Throwable cause) {
        super(message, cause);
        this.jsonRpcCode = jsonRpcCode;
        this.reasonCode = reasonCode;
    }

    int jsonRpcCode() {
        return jsonRpcCode;
    }

    String reasonCode() {
        return reasonCode;
    }

    static CodingHostException invalidParams(String message) {
        return new CodingHostException(-32602, "INVALID_PARAMS", message);
    }

    static CodingHostException unknownMethod(String method) {
        return new CodingHostException(-32601, "REMOTE_METHOD_UNSUPPORTED", "Unknown method: " + method);
    }

    static CodingHostException unsupported(String message) {
        return new CodingHostException(-32601, "REMOTE_METHOD_UNSUPPORTED", message);
    }

    static CodingHostException conflict(String reasonCode, String message) {
        return new CodingHostException(-32009, reasonCode, message);
    }

    static CodingHostException notFound(String reasonCode, String message) {
        return new CodingHostException(-32004, reasonCode, message);
    }

    static CodingHostException internal(String message) {
        return new CodingHostException(-32603, "INTERNAL_ERROR", message);
    }
}

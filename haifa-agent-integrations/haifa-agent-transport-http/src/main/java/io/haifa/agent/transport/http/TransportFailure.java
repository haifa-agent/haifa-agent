package io.haifa.agent.transport.http;

import io.haifa.agent.runtime.api.RuntimeErrorCode;

final class TransportFailure extends RuntimeException {
    private final RuntimeErrorCode code;
    private final int status;

    TransportFailure(RuntimeErrorCode code, int status, String safeMessage) {
        super(safeMessage);
        this.code = java.util.Objects.requireNonNull(code);
        this.status = status;
    }

    RuntimeErrorCode code() {
        return code;
    }

    int status() {
        return status;
    }
}

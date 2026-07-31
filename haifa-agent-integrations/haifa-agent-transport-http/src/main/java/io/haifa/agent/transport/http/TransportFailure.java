package io.haifa.agent.transport.http;

import io.haifa.agent.runtime.api.RuntimeApiErrorCode;

final class TransportFailure extends RuntimeException {
    private final RuntimeApiErrorCode code;
    private final int status;

    TransportFailure(RuntimeApiErrorCode code, int status, String safeMessage) {
        super(safeMessage);
        this.code = java.util.Objects.requireNonNull(code);
        this.status = status;
    }

    RuntimeApiErrorCode code() {
        return code;
    }

    int status() {
        return status;
    }
}

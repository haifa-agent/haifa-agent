package io.haifa.agent.runtime.api;

import java.util.Objects;

/** Stable machine-readable failure without internal implementation details. */
public final class RuntimeContractException extends RuntimeException {
    private final RuntimeApiErrorCode code;

    public RuntimeContractException(RuntimeApiErrorCode code, String safeMessage) {
        super(InteractionOption.requireText(safeMessage, "safeMessage", 512));
        this.code = Objects.requireNonNull(code, "code must not be null");
    }

    public RuntimeApiErrorCode code() {
        return code;
    }
}

package io.haifa.agent.runtime.api;

import java.util.Objects;

/** Stable machine-readable failure without internal implementation details. */
public final class RuntimeContractException extends RuntimeException {
    private final RuntimeErrorCode code;

    public RuntimeContractException(RuntimeErrorCode code, String safeMessage) {
        super(InteractionOption.requireText(safeMessage, "safeMessage", 512));
        this.code = Objects.requireNonNull(code, "code must not be null");
    }

    public RuntimeErrorCode code() {
        return code;
    }
}

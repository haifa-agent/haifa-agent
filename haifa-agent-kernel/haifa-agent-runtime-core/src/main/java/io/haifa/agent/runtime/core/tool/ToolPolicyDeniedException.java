package io.haifa.agent.runtime.core.tool;

import java.util.Objects;

/** Internal signal for a terminal policy denial that must not consume an agent repair attempt. */
public final class ToolPolicyDeniedException extends SecurityException {
    private static final long serialVersionUID = 1L;

    private final String failureCode;

    public ToolPolicyDeniedException(String failureCode) {
        super("Tool request was denied by policy");
        this.failureCode = Objects.requireNonNull(failureCode, "failureCode must not be null");
        if (!failureCode.matches("[A-Z][A-Z0-9_]{2,127}")) {
            throw new IllegalArgumentException("failureCode must be a stable uppercase code");
        }
    }

    public String failureCode() {
        return failureCode;
    }
}

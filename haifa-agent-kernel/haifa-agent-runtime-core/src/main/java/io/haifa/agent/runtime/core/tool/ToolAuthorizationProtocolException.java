package io.haifa.agent.runtime.core.tool;

import java.util.Objects;

/** Internal safe diagnostic for a request that cannot be expressed by the selected Tool protocol. */
public final class ToolAuthorizationProtocolException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    private final String reasonCode;
    private final String safeExplanation;

    public ToolAuthorizationProtocolException(String reasonCode, String safeExplanation) {
        super("tool request does not satisfy the authorization protocol");
        this.reasonCode = Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        this.safeExplanation = Objects.requireNonNull(safeExplanation, "safeExplanation must not be null");
    }

    public String reasonCode() {
        return reasonCode;
    }

    public String safeExplanation() {
        return safeExplanation;
    }
}

package io.haifa.agent.model.api;

import java.util.Objects;

/** Safe normalized provider failure. The message must never include credentials or raw payloads. */
public final class ModelInvocationException extends RuntimeException {
    private final ModelErrorCategory category;
    private final boolean retryable;
    private final int httpStatus;
    private final String providerCode;
    private final ModelCallId callId;

    public ModelInvocationException(
            ModelErrorCategory category,
            boolean retryable,
            int httpStatus,
            String providerCode,
            ModelCallId callId,
            String safeMessage,
            Throwable cause) {
        super(ModelValues.text(safeMessage, "safeMessage"), cause);
        this.category = Objects.requireNonNull(category, "category must not be null");
        this.retryable = retryable;
        this.httpStatus = httpStatus;
        this.providerCode = Objects.requireNonNull(providerCode, "providerCode must not be null")
                .trim();
        this.callId = Objects.requireNonNull(callId, "callId must not be null");
    }

    public ModelErrorCategory category() {
        return category;
    }

    public boolean retryable() {
        return retryable;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String providerCode() {
        return providerCode;
    }

    public ModelCallId callId() {
        return callId;
    }
}

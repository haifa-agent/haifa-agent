package io.haifa.agent.model.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Safe normalized provider failure. The message must never include credentials or raw payloads. */
public final class ModelInvocationException extends RuntimeException {
    private final ModelErrorCategory category;
    private final boolean retryable;
    private final int httpStatus;
    private final String providerCode;
    private final ModelCallId callId;
    private final Duration retryAfter;
    private final boolean outputObserved;

    public ModelInvocationException(
            ModelErrorCategory category,
            boolean retryable,
            int httpStatus,
            String providerCode,
            ModelCallId callId,
            String safeMessage,
            Throwable cause) {
        this(category, retryable, httpStatus, providerCode, callId, safeMessage, cause, null, false);
    }

    public ModelInvocationException(
            ModelErrorCategory category,
            boolean retryable,
            int httpStatus,
            String providerCode,
            ModelCallId callId,
            String safeMessage,
            Throwable cause,
            Duration retryAfter,
            boolean outputObserved) {
        super(ModelValues.text(safeMessage, "safeMessage"), cause);
        this.category = Objects.requireNonNull(category, "category must not be null");
        this.retryable = retryable;
        this.httpStatus = httpStatus;
        this.providerCode = Objects.requireNonNull(providerCode, "providerCode must not be null")
                .trim();
        this.callId = Objects.requireNonNull(callId, "callId must not be null");
        if (retryAfter != null && retryAfter.isNegative()) {
            throw new IllegalArgumentException("retryAfter must not be negative");
        }
        this.retryAfter = retryAfter;
        this.outputObserved = outputObserved;
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

    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    /** Safe diagnostic projection that saturates instead of overflowing on an untrusted duration. */
    public OptionalLong retryAfterMillis() {
        if (retryAfter == null) return OptionalLong.empty();
        try {
            return OptionalLong.of(retryAfter.toMillis());
        } catch (ArithmeticException ignored) {
            return OptionalLong.of(Long.MAX_VALUE);
        }
    }

    public boolean outputObserved() {
        return outputObserved;
    }

    public ModelInvocationException withOutputObserved() {
        if (outputObserved) return this;
        return new ModelInvocationException(
                category, retryable, httpStatus, providerCode, callId, getMessage(), this, retryAfter, true);
    }

    public ModelInvocationException asPartialResponse() {
        if (category == ModelErrorCategory.PARTIAL_RESPONSE && outputObserved) return this;
        return new ModelInvocationException(
                ModelErrorCategory.PARTIAL_RESPONSE,
                false,
                httpStatus,
                providerCode,
                callId,
                getMessage(),
                this,
                retryAfter,
                true);
    }
}

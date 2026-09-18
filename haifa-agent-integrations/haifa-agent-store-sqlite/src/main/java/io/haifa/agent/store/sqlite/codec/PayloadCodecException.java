package io.haifa.agent.store.sqlite.codec;

import java.util.Objects;

public final class PayloadCodecException extends RuntimeException {
    private final PayloadCodecFailure failure;

    public PayloadCodecException(PayloadCodecFailure failure, String message) {
        super(message);
        this.failure = Objects.requireNonNull(failure, "failure must not be null");
    }

    public PayloadCodecException(PayloadCodecFailure failure, String message, Throwable cause) {
        super(message, cause);
        this.failure = Objects.requireNonNull(failure, "failure must not be null");
    }

    public PayloadCodecFailure failure() {
        return failure;
    }
}

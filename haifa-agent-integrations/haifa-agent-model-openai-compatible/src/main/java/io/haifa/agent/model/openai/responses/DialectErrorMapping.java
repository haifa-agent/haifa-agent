package io.haifa.agent.model.openai.responses;

import io.haifa.agent.model.api.ModelErrorCategory;
import io.haifa.agent.model.api.ModelErrorMapping;
import java.time.Duration;
import java.util.Optional;

record DialectErrorMapping(
        ModelErrorCategory category,
        boolean retryable,
        String providerCode,
        String safeMessage,
        Optional<Duration> retryAfter,
        Optional<String> providerRequestId) {

    DialectErrorMapping(
            ModelErrorCategory category,
            boolean retryable,
            String providerCode,
            String safeMessage,
            Optional<Duration> retryAfter) {
        this(category, retryable, providerCode, safeMessage, retryAfter, Optional.empty());
    }

    static DialectErrorMapping of(
            ModelErrorCategory category, boolean retryable, String providerCode, String safeMessage) {
        return new DialectErrorMapping(
                category, retryable, providerCode, safeMessage, Optional.empty(), Optional.empty());
    }

    static DialectErrorMapping of(
            ModelErrorCategory category,
            boolean retryable,
            String providerCode,
            String safeMessage,
            Duration retryAfter) {
        return new DialectErrorMapping(
                category, retryable, providerCode, safeMessage, Optional.ofNullable(retryAfter), Optional.empty());
    }

    static DialectErrorMapping from(ModelErrorMapping mapping) {
        return new DialectErrorMapping(
                mapping.category(),
                mapping.retryable(),
                mapping.providerCode(),
                mapping.safeMessage(),
                mapping.retryAfter(),
                mapping.providerRequestId());
    }
}

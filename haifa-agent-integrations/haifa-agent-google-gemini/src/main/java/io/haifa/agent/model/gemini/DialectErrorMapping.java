package io.haifa.agent.model.gemini;

import io.haifa.agent.model.api.ModelErrorCategory;
import java.time.Duration;
import java.util.Optional;

record DialectErrorMapping(
        ModelErrorCategory category,
        boolean retryable,
        String providerCode,
        String safeMessage,
        Optional<Duration> retryAfter) {

    static DialectErrorMapping of(
            ModelErrorCategory category, boolean retryable, String providerCode, String safeMessage) {
        return new DialectErrorMapping(category, retryable, providerCode, safeMessage, Optional.empty());
    }

    static DialectErrorMapping of(
            ModelErrorCategory category,
            boolean retryable,
            String providerCode,
            String safeMessage,
            Duration retryAfter) {
        return new DialectErrorMapping(category, retryable, providerCode, safeMessage, Optional.ofNullable(retryAfter));
    }
}

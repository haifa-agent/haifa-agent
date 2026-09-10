package io.haifa.agent.web;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record WebProviderInvocationContext(
        Instant deadline,
        WebCancellation cancellation,
        Map<String, String> credentials,
        WebInvocationObserver observer) {
    public WebProviderInvocationContext {
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(cancellation, "cancellation");
        credentials = Map.copyOf(Objects.requireNonNull(credentials, "credentials"));
        Objects.requireNonNull(observer, "observer");
    }
}

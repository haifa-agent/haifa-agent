package io.haifa.agent.application.project.tool.web;

import io.haifa.agent.credential.api.CredentialLease;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record WebProviderInvocationContext(
        Instant deadline,
        WebCancellation cancellation,
        List<CredentialLease> credentialLeases,
        WebInvocationObserver observer) {
    public WebProviderInvocationContext {
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(cancellation, "cancellation");
        credentialLeases = List.copyOf(Objects.requireNonNull(credentialLeases, "credentialLeases"));
        Objects.requireNonNull(observer, "observer");
    }
}

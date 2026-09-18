package io.haifa.agent.policy.api;

import java.util.Objects;

public record PolicyRequest(
        PolicySubject subject, PolicyContext context, PolicyAction action, PolicyResource resource, PolicyRisk risk) {
    public PolicyRequest {
        subject = Objects.requireNonNull(subject, "subject must not be null");
        context = Objects.requireNonNull(context, "context must not be null");
        action = Objects.requireNonNull(action, "action must not be null");
        resource = Objects.requireNonNull(resource, "resource must not be null");
        risk = Objects.requireNonNull(risk, "risk must not be null");
    }
}

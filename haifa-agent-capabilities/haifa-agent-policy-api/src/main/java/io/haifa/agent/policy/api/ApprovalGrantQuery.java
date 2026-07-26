package io.haifa.agent.policy.api;

import java.util.Objects;

public record ApprovalGrantQuery(
        PolicySubject subject, PolicyContext context, PolicyAction action, ApprovalTargetRef target) {
    public ApprovalGrantQuery {
        subject = Objects.requireNonNull(subject, "subject must not be null");
        context = Objects.requireNonNull(context, "context must not be null");
        action = Objects.requireNonNull(action, "action must not be null");
        target = Objects.requireNonNull(target, "target must not be null");
    }
}

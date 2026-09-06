package io.haifa.agent.project.snapshot;

import java.util.Objects;

public record WorkspaceDriftDecision(WorkspaceDriftKind kind, boolean automaticRestoreAllowed, String reasonCode) {
    public WorkspaceDriftDecision {
        kind = Objects.requireNonNull(kind, "kind must not be null");
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode must not be null")
                .trim();
        if (reasonCode.isEmpty()) throw new IllegalArgumentException("reasonCode must not be blank");
    }
}

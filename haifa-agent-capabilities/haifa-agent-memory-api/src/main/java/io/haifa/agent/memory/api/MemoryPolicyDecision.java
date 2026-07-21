package io.haifa.agent.memory.api;

import java.util.Objects;
import java.util.Set;

public record MemoryPolicyDecision(
        boolean sensitive, boolean automaticApprovalAllowed, Set<MemorySecurityLabel> labels, String reason) {
    public MemoryPolicyDecision {
        labels = Set.copyOf(Objects.requireNonNull(labels));
        reason = MemoryValues.text(reason, "reason", 512);
        if (sensitive && automaticApprovalAllowed) {
            throw new IllegalArgumentException("sensitive memory cannot be automatically approved");
        }
    }
}

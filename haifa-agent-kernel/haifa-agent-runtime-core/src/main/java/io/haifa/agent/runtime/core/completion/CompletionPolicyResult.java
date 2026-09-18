package io.haifa.agent.runtime.core.completion;

import java.util.List;
import java.util.Objects;

/** Product-owned completion policy result consumed by Runtime without product type dependencies. */
public record CompletionPolicyResult(List<CompletionBlocker> blockers, List<String> evidenceCodes) {
    public CompletionPolicyResult {
        blockers = List.copyOf(Objects.requireNonNull(blockers, "blockers must not be null"));
        evidenceCodes = List.copyOf(Objects.requireNonNull(evidenceCodes, "evidenceCodes must not be null"));
        if (blockers.size() > 32 || evidenceCodes.size() > 32) {
            throw new IllegalArgumentException("completion policy result is too large");
        }
    }

    public static CompletionPolicyResult accepted() {
        return new CompletionPolicyResult(List.of(), List.of());
    }

    public static CompletionPolicyResult accepted(List<String> evidenceCodes) {
        return new CompletionPolicyResult(List.of(), evidenceCodes);
    }

    public static CompletionPolicyResult blocked(List<CompletionBlocker> blockers, List<String> evidenceCodes) {
        if (Objects.requireNonNull(blockers, "blockers must not be null").isEmpty()) {
            throw new IllegalArgumentException("blocked result must contain blockers");
        }
        return new CompletionPolicyResult(blockers, evidenceCodes);
    }

    public boolean allowed() {
        return blockers.isEmpty();
    }
}

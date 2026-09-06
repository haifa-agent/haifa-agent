package io.haifa.agent.project.patch;

import io.haifa.agent.project.mutation.MutationResult;
import java.util.List;
import java.util.Objects;

public record PatchApplyResult(
        String patchSha256, List<MutationResult> appliedMutations, List<PatchConflict> conflicts, boolean complete) {
    public PatchApplyResult {
        patchSha256 = Objects.requireNonNull(patchSha256, "patchSha256 must not be null");
        appliedMutations = List.copyOf(Objects.requireNonNull(appliedMutations, "appliedMutations must not be null"));
        conflicts = List.copyOf(Objects.requireNonNull(conflicts, "conflicts must not be null"));
        if (complete && !conflicts.isEmpty())
            throw new IllegalArgumentException("complete patch cannot have conflicts");
    }
}

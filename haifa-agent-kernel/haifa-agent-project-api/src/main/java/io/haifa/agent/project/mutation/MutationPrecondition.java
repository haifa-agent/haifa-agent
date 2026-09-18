package io.haifa.agent.project.mutation;

import io.haifa.agent.project.workspace.WorkspaceRevision;
import java.util.Objects;
import java.util.Optional;

public record MutationPrecondition(
        WorkspaceRevision expectedWorkspaceRevision, String expectedContentHash, boolean requireAbsent) {
    public MutationPrecondition {
        if (expectedContentHash != null) {
            expectedContentHash = expectedContentHash.trim();
            if (expectedContentHash.isEmpty())
                throw new IllegalArgumentException("expectedContentHash must not be blank");
        }
        if (requireAbsent && expectedContentHash != null) {
            throw new IllegalArgumentException("absent precondition cannot include a content hash");
        }
    }

    public static MutationPrecondition absent(WorkspaceRevision revision) {
        return new MutationPrecondition(revision, null, true);
    }

    public static MutationPrecondition existing(WorkspaceRevision revision, String contentHash) {
        return new MutationPrecondition(
                revision, Objects.requireNonNull(contentHash, "contentHash must not be null"), false);
    }

    public Optional<WorkspaceRevision> optionalWorkspaceRevision() {
        return Optional.ofNullable(expectedWorkspaceRevision);
    }

    public Optional<String> optionalContentHash() {
        return Optional.ofNullable(expectedContentHash);
    }
}

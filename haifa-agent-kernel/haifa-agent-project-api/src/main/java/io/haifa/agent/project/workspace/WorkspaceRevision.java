package io.haifa.agent.project.workspace;

import java.util.Objects;

public record WorkspaceRevision(long sequence, String digest) {
    public WorkspaceRevision {
        if (sequence < 0) throw new IllegalArgumentException("sequence must not be negative");
        digest = Objects.requireNonNull(digest, "digest must not be null").trim();
        if (digest.isEmpty()) throw new IllegalArgumentException("digest must not be blank");
    }

    public static WorkspaceRevision initial(String digest) {
        return new WorkspaceRevision(0, digest);
    }
}

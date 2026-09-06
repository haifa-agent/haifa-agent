package io.haifa.agent.project.mutation;

import io.haifa.agent.project.path.WorkspacePath;
import java.util.Objects;

public record MoveFileRequest(
        WorkspacePath source,
        WorkspacePath destination,
        MutationPrecondition sourcePrecondition,
        MutationContext context) {
    public MoveFileRequest {
        source = Objects.requireNonNull(source, "source must not be null");
        destination = Objects.requireNonNull(destination, "destination must not be null");
        sourcePrecondition = Objects.requireNonNull(sourcePrecondition, "sourcePrecondition must not be null");
        context = Objects.requireNonNull(context, "context must not be null");
        if (!source.workspaceId().equals(destination.workspaceId())) {
            throw new IllegalArgumentException("cross-workspace move is unsupported");
        }
        if (sourcePrecondition.requireAbsent() || sourcePrecondition.expectedContentHash() == null) {
            throw new IllegalArgumentException("move requires an expected source hash");
        }
    }
}

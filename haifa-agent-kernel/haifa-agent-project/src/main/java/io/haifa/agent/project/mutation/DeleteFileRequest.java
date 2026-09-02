package io.haifa.agent.project.mutation;

import io.haifa.agent.project.path.WorkspacePath;
import java.util.Objects;

public record DeleteFileRequest(WorkspacePath path, MutationPrecondition precondition, MutationContext context) {
    public DeleteFileRequest {
        path = Objects.requireNonNull(path, "path must not be null");
        precondition = Objects.requireNonNull(precondition, "precondition must not be null");
        context = Objects.requireNonNull(context, "context must not be null");
        if (precondition.requireAbsent() || precondition.expectedContentHash() == null) {
            throw new IllegalArgumentException("delete requires an expected content hash");
        }
    }
}

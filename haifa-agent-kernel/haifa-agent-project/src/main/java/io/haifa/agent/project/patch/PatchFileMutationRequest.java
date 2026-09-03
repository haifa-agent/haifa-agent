package io.haifa.agent.project.patch;

import io.haifa.agent.project.mutation.MutationContext;
import io.haifa.agent.project.mutation.MutationPrecondition;
import io.haifa.agent.project.path.WorkspacePath;
import java.util.Objects;

public record PatchFileMutationRequest(
        WorkspacePath path,
        FilePatch patch,
        MutationPrecondition precondition,
        MutationContext context,
        long maxOutputBytes) {
    public PatchFileMutationRequest {
        path = Objects.requireNonNull(path, "path must not be null");
        patch = Objects.requireNonNull(patch, "patch must not be null");
        precondition = Objects.requireNonNull(precondition, "precondition must not be null");
        context = Objects.requireNonNull(context, "context must not be null");
        if (maxOutputBytes < 1) throw new IllegalArgumentException("maxOutputBytes must be positive");
        if (!path.equals(patch.sourcePath())) {
            throw new IllegalArgumentException("patch source path does not match mutation path");
        }
        if (patch.creation() || patch.deletion()) {
            throw new IllegalArgumentException("streaming patch mutation requires an existing output file");
        }
    }
}

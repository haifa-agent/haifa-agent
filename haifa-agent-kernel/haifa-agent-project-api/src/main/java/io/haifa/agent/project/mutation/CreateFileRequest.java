package io.haifa.agent.project.mutation;

import io.haifa.agent.project.path.WorkspacePath;
import java.util.Arrays;
import java.util.Objects;

public record CreateFileRequest(
        WorkspacePath path, byte[] content, MutationPrecondition precondition, MutationContext context) {
    public CreateFileRequest {
        path = Objects.requireNonNull(path, "path must not be null");
        content = Arrays.copyOf(Objects.requireNonNull(content, "content must not be null"), content.length);
        precondition = Objects.requireNonNull(precondition, "precondition must not be null");
        context = Objects.requireNonNull(context, "context must not be null");
        if (!precondition.requireAbsent()) throw new IllegalArgumentException("create requires an absent precondition");
    }

    @Override
    public byte[] content() {
        return Arrays.copyOf(content, content.length);
    }
}

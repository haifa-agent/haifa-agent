package io.haifa.agent.project.mutation;

import io.haifa.agent.project.path.WorkspacePath;
import java.util.Arrays;
import java.util.Objects;

public record WriteFileRequest(
        WorkspacePath path, byte[] content, MutationPrecondition precondition, MutationContext context) {
    public WriteFileRequest {
        path = Objects.requireNonNull(path, "path must not be null");
        content = Arrays.copyOf(Objects.requireNonNull(content, "content must not be null"), content.length);
        precondition = Objects.requireNonNull(precondition, "precondition must not be null");
        context = Objects.requireNonNull(context, "context must not be null");
        if (precondition.requireAbsent() || precondition.expectedContentHash() == null) {
            throw new IllegalArgumentException("write requires an expected content hash");
        }
    }

    @Override
    public byte[] content() {
        return Arrays.copyOf(content, content.length);
    }
}

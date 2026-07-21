package io.haifa.agent.project.quarantine;

import io.haifa.agent.project.mutation.MutationContext;
import io.haifa.agent.project.path.WorkspacePath;
import java.util.Objects;

public record QuarantineRestoreRequest(String token, WorkspacePath destination, MutationContext context) {
    public QuarantineRestoreRequest {
        token = Objects.requireNonNull(token, "token must not be null").trim();
        if (token.isEmpty()) throw new IllegalArgumentException("token must not be blank");
        destination = Objects.requireNonNull(destination, "destination must not be null");
        context = Objects.requireNonNull(context, "context must not be null");
    }
}

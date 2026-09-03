package io.haifa.agent.git;

import io.haifa.agent.execution.api.TrustedExecutionContext;
import java.util.Objects;

public record GitCommandContext(TrustedExecutionContext executionContext, GitExecutionAuthorizer authorizer) {
    public GitCommandContext(TrustedExecutionContext executionContext) {
        this(executionContext, GitExecutionAuthorizer.existingDecision());
    }

    public GitCommandContext {
        executionContext = Objects.requireNonNull(executionContext, "executionContext must not be null");
        authorizer = Objects.requireNonNull(authorizer, "authorizer must not be null");
        if (!executionContext.allows("git.read")) throw new IllegalArgumentException("git.read capability is required");
    }
}

package io.haifa.agent.execution.core.tool;

import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import java.util.Objects;
import java.util.Set;

@FunctionalInterface
public interface ExecutionInvocationScopeResolver {
    ExecutionInvocationScope resolve(ToolInvocationRequest invocation);

    record ExecutionInvocationScope(WorkspaceId workspaceId, Set<String> capabilities) {
        public ExecutionInvocationScope {
            workspaceId = Objects.requireNonNull(workspaceId, "workspaceId must not be null");
            capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities must not be null"));
        }
    }
}

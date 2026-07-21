package io.haifa.agent.application.project.tool;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import io.haifa.agent.runtime.core.tool.ToolDefinition;
import io.haifa.agent.runtime.core.tool.ToolExecutor;
import java.util.Objects;

/** Adapter installed into the existing Runtime ToolPipeline; it is not a registry or policy engine. */
public final class ProjectToolExecutor implements ToolExecutor {
    private final RunWorkspaceAccessResolver access;
    private final ProjectToolOperations operations;

    public ProjectToolExecutor(RunWorkspaceAccessResolver access, ProjectToolOperations operations) {
        this.access = Objects.requireNonNull(access, "access must not be null");
        this.operations = Objects.requireNonNull(operations, "operations must not be null");
    }

    @Override
    public ToolResult execute(AgentRun run, ToolDefinition definition, ToolRequest request) {
        if (run.project().isEmpty()) throw new SecurityException("project tool requires a project-bound run");
        RunWorkspaceAccess binding = access.resolve(run);
        return operations.execute(
                definition.name(),
                binding.workspaceId(),
                run.principal(),
                run.id().value(),
                binding.policyDecisionRef(),
                request.arguments());
    }
}

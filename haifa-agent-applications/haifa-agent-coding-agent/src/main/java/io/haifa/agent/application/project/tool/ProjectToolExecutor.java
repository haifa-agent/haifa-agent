package io.haifa.agent.application.project.tool;

import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolProvider;
import io.haifa.agent.tool.api.ToolProviderId;
import io.haifa.agent.tool.api.ToolReconciliation;
import io.haifa.agent.tool.api.ToolReconciliationRequest;
import java.util.Objects;

/** Adapter installed into the existing Runtime ToolPipeline; it is not a registry or policy engine. */
public final class ProjectToolExecutor implements ToolProvider {
    public static final ToolProviderId PROVIDER_ID = new ToolProviderId("haifa-project");
    private final RunWorkspaceAccessResolver access;
    private final ProjectToolOperations operations;
    private final ProjectExecutionToolOperations executionOperations;
    private final ProjectWorktreeToolOperations worktreeOperations;

    public ProjectToolExecutor(RunWorkspaceAccessResolver access, ProjectToolOperations operations) {
        this(access, operations, null, null);
    }

    public ProjectToolExecutor(
            RunWorkspaceAccessResolver access,
            ProjectToolOperations operations,
            ProjectExecutionToolOperations executionOperations) {
        this(access, operations, executionOperations, null);
    }

    public ProjectToolExecutor(
            RunWorkspaceAccessResolver access,
            ProjectToolOperations operations,
            ProjectExecutionToolOperations executionOperations,
            ProjectWorktreeToolOperations worktreeOperations) {
        this.access = Objects.requireNonNull(access, "access must not be null");
        this.operations = Objects.requireNonNull(operations, "operations must not be null");
        this.executionOperations = executionOperations;
        this.worktreeOperations = worktreeOperations;
    }

    @Override
    public ToolProviderId id() {
        return PROVIDER_ID;
    }

    @Override
    public ToolResult invoke(ToolInvocationRequest request) {
        RunWorkspaceAccess binding = access.resolve(request.runId(), request.principal());
        var requiredCapabilities = request.binding().definition().resources().filesystemCapabilities();
        if (!binding.capabilities().containsAll(requiredCapabilities)) {
            throw new SecurityException("run workspace access does not authorize the frozen tool capability");
        }
        String toolName = request.binding().definition().name().value();
        ToolResult result;
        if (toolName.equals("execution.run")) {
            if (executionOperations == null) {
                throw new IllegalStateException("execution.run is not configured for this application");
            }
            return executionOperations.execute(request, binding);
        } else if (toolName.equals(ProjectWorktreeToolOperations.TOOL_NAME)) {
            if (worktreeOperations == null) {
                throw new IllegalStateException("workspace.worktree.create is not configured for this application");
            }
            return worktreeOperations.execute(request, binding);
        } else {
            request.observer().dispatched();
            result = operations.execute(
                    new ProjectToolCallContext(
                            request.tenant(),
                            binding.workspaceId(),
                            request.principal(),
                            request.runId().value(),
                            request.toolCallId().value(),
                            request.idempotencyKey().orElse(request.toolCallId().value())),
                    toolName,
                    request.arguments());
            request.observer().acknowledged();
            return result;
        }
    }

    @Override
    public ToolReconciliation reconcile(ToolReconciliationRequest request) {
        RunWorkspaceAccess binding = access.resolve(request.runId(), request.principal());
        var requiredCapabilities = request.binding().definition().resources().filesystemCapabilities();
        if (!binding.capabilities().containsAll(requiredCapabilities)) {
            throw new SecurityException("run workspace access does not authorize reconciliation");
        }
        String toolName = request.binding().definition().name().value();
        if (toolName.equals("execution.run") && executionOperations != null) {
            return executionOperations.reconcile(request, binding);
        }
        return operations.reconcile(
                toolName,
                binding.workspaceId(),
                request.principal(),
                request.runId().value(),
                request.toolCallId().value(),
                request.idempotencyKey(),
                request.arguments());
    }
}

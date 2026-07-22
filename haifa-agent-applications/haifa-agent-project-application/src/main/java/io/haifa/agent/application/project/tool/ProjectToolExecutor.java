package io.haifa.agent.application.project.tool;

import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolProvider;
import io.haifa.agent.tool.api.ToolProviderId;
import java.util.Objects;

/** Adapter installed into the existing Runtime ToolPipeline; it is not a registry or policy engine. */
public final class ProjectToolExecutor implements ToolProvider {
    public static final ToolProviderId PROVIDER_ID = new ToolProviderId("haifa-project");
    private final RunWorkspaceAccessResolver access;
    private final ProjectToolOperations operations;
    private final ProjectExecutionToolOperations executionOperations;

    public ProjectToolExecutor(RunWorkspaceAccessResolver access, ProjectToolOperations operations) {
        this(access, operations, null);
    }

    public ProjectToolExecutor(
            RunWorkspaceAccessResolver access,
            ProjectToolOperations operations,
            ProjectExecutionToolOperations executionOperations) {
        this.access = Objects.requireNonNull(access, "access must not be null");
        this.operations = Objects.requireNonNull(operations, "operations must not be null");
        this.executionOperations = executionOperations;
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
        request.observer().dispatched();
        String toolName = request.binding().definition().name().value();
        ToolResult result;
        if (toolName.equals("execution.run")) {
            if (executionOperations == null) {
                throw new IllegalStateException("execution.run is not configured for this application");
            }
            result = executionOperations.execute(request, binding);
        } else {
            result = operations.execute(
                    toolName,
                    binding.workspaceId(),
                    request.principal(),
                    request.runId().value(),
                    binding.policyDecisionRef(),
                    request.arguments());
        }
        request.observer().acknowledged();
        return result;
    }
}

package io.haifa.agent.personalassistant.application.execution;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.execution.api.ExecutionOrigin;
import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.execution.core.ExecutionPolicy;
import io.haifa.agent.execution.core.ExecutionPolicyEntryPoint;
import io.haifa.agent.execution.core.ExecutionRejectedException;
import io.haifa.agent.execution.core.tool.ExecutionToolConfiguration;
import io.haifa.agent.execution.core.tool.ExecutionToolProvider;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.runtime.core.tool.RuntimeToolExecutionVerifier;
import java.util.Objects;

/** PA allows only an exactly reconstructed Runtime Tool request against its server-owned assembly. */
public final class PersonalAssistantExecutionPolicy implements ExecutionPolicy {
    private final RuntimeToolExecutionVerifier runtime;
    private final ExecutionToolConfiguration configuration;
    private final TenantRef tenant;
    private final PrincipalRef principal;
    private final WorkspaceId workspaceId;

    public PersonalAssistantExecutionPolicy(
            RuntimeToolExecutionVerifier runtime,
            ExecutionToolConfiguration configuration,
            TenantRef tenant,
            PrincipalRef principal,
            WorkspaceId workspaceId) {
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        this.principal = Objects.requireNonNull(principal, "principal must not be null");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId must not be null");
    }

    @Override
    public void authorize(ExecutionRequest request, ExecutionPolicyEntryPoint entryPoint) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(entryPoint, "entryPoint must not be null");
        if (entryPoint == ExecutionPolicyEntryPoint.MANAGED_SESSION) {
            throw denied("PERSONAL_MANAGED_EXECUTION_DENIED", "Personal Assistant has no managed execution entry");
        }
        if (request.context().origin() != ExecutionOrigin.RUNTIME_TOOL
                || request.context().sourceToolCallId().isEmpty()
                || !request.context().allows("execution.run")) {
            throw denied("PERSONAL_EXECUTION_ORIGIN_DENIED", "Personal Assistant requires a Runtime Tool source");
        }
        if (!request.context().tenant().equals(tenant)
                || !request.context().actor().equals(principal)
                || !request.workspaceId().equals(workspaceId)
                || !request.workingDirectory().workspaceId().equals(workspaceId)
                || !request.workingDirectory().projectPath().isRoot()) {
            throw denied("PERSONAL_EXECUTION_ASSEMBLY_DENIED", "Personal Assistant execution target is not fixed");
        }
        try {
            runtime.verify(
                    request.context().tenant(),
                    request.context().runRef(),
                    request.context().actor(),
                    request.context().sourceToolCallId().orElseThrow(),
                    (frozenConfiguration, binding, toolRequest) -> {
                        if (!"execution.run".equals(binding.definition().name().value())
                                || !frozenConfiguration.toolBindings().contains(binding)) {
                            throw new SecurityException("source Tool is not the frozen PA execution capability");
                        }
                        ExecutionToolProvider.validateFrozenInvocation(configuration, toolRequest.arguments(), request);
                    });
        } catch (SecurityException exception) {
            throw denied("PERSONAL_EXECUTION_SOURCE_DENIED", exception.getMessage());
        }
    }

    private static ExecutionRejectedException denied(String code, String message) {
        return new ExecutionRejectedException(code, message);
    }
}

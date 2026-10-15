package io.haifa.agent.starter;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.execution.api.ExecutionOrigin;
import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.execution.core.ExecutionPolicy;
import io.haifa.agent.execution.core.ExecutionPolicyEntryPoint;
import io.haifa.agent.execution.core.ExecutionRejectedException;
import io.haifa.agent.execution.core.tool.ExecutionToolConfiguration;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.util.Objects;

/**
 * Trusted-local policy: only an exactly reconstructed Runtime Tool request against one frozen
 * assembly reaches the Sandbox. It cannot prove the Tool Call source, because that verification
 * lives in the Runtime and the process-local entry has no Runtime component; the model boundary
 * stays the Tool provider, the Approval and the frozen invocation digest.
 */
final class FrozenHostExecutionPolicy implements ExecutionPolicy {
    private final ExecutionToolConfiguration configuration;
    private final TenantRef tenant;
    private final PrincipalRef principal;
    private final WorkspaceId workspaceId;
    private final String managedSessionCode;
    private final String originCode;
    private final String assemblyCode;

    FrozenHostExecutionPolicy(
            ExecutionToolConfiguration configuration,
            TenantRef tenant,
            PrincipalRef principal,
            WorkspaceId workspaceId) {
        this(
                configuration,
                tenant,
                principal,
                workspaceId,
                "HOST_EXECUTION_MANAGED_SESSION_DENIED",
                "HOST_EXECUTION_ORIGIN_DENIED",
                "HOST_EXECUTION_ASSEMBLY_DENIED");
    }

    FrozenHostExecutionPolicy(
            ExecutionToolConfiguration configuration,
            TenantRef tenant,
            PrincipalRef principal,
            WorkspaceId workspaceId,
            String managedSessionCode,
            String originCode,
            String assemblyCode) {
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        this.principal = Objects.requireNonNull(principal, "principal must not be null");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        this.managedSessionCode = Objects.requireNonNull(managedSessionCode, "managedSessionCode must not be null");
        this.originCode = Objects.requireNonNull(originCode, "originCode must not be null");
        this.assemblyCode = Objects.requireNonNull(assemblyCode, "assemblyCode must not be null");
    }

    @Override
    public void authorize(ExecutionRequest request, ExecutionPolicyEntryPoint entryPoint) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(entryPoint, "entryPoint must not be null");
        if (entryPoint == ExecutionPolicyEntryPoint.MANAGED_SESSION) {
            throw denied(managedSessionCode, "the declared platform has no managed session");
        }
        if (request.context().origin() != ExecutionOrigin.RUNTIME_TOOL
                || request.context().sourceToolCallId().isEmpty()
                || !request.context().allows("execution_run")) {
            throw denied(originCode, "the declared platform requires a Runtime Tool source");
        }
        if (!request.context().tenant().equals(tenant)
                || !request.context().actor().equals(principal)
                || !request.workspaceId().equals(workspaceId)
                || !request.workingDirectory().workspaceId().equals(workspaceId)
                || !request.workingDirectory().projectPath().isRoot()
                || !request.environmentRef().equals(configuration.environmentRef())
                || !request.sandboxProfileRef().equals(configuration.sandboxProfileRef())
                || !request.scratchSpace().equals(configuration.scratchSpace())
                || request.limits().timeout().compareTo(configuration.maximumTimeout()) > 0
                || !Objects.equals(request.limits().maxProcesses(), configuration.maximumProcesses())) {
            throw denied(assemblyCode, "execution target is not the frozen assembly");
        }
    }

    private static ExecutionRejectedException denied(String code, String message) {
        return new ExecutionRejectedException(code, message);
    }
}

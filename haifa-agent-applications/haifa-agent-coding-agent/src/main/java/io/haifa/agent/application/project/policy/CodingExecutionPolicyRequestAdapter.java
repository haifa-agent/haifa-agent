package io.haifa.agent.application.project.policy;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.execution.core.command.CredentialEgressGuard;
import io.haifa.agent.policy.api.ApprovalMode;
import io.haifa.agent.policy.api.PolicyRequest;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import io.haifa.agent.runtime.core.tool.DefaultToolPolicyRequestAdapter;
import io.haifa.agent.runtime.core.tool.ToolAuthorizationProtocolException;
import io.haifa.agent.runtime.core.tool.ToolPolicyRequestAdapter;
import io.haifa.agent.tool.api.FrozenToolBinding;
import java.util.Objects;

/**
 * Coding-owned execution policy request adapter. Approval uses the generic execution baseline from
 * the frozen Tool definition; this adapter only keeps the narrow credential fail-closed boundary so
 * confirmed host-credential read, echo, override, or redirect paths never reach the model context.
 *
 * <p>It does not classify Git/GitHub commands, infer business effects, or adjust risk. The model
 * understands commands and their output; the generic execution path owns workspace authorization,
 * approval, timeout, cancellation, output handling, and unknown-outcome handling.</p>
 */
public final class CodingExecutionPolicyRequestAdapter implements ToolPolicyRequestAdapter {
    static final String EXECUTION_RUN = "execution_run";
    private static final String PRODUCT_ID = "haifa-coding-agent";

    private final DefaultToolPolicyRequestAdapter delegate;

    public CodingExecutionPolicyRequestAdapter(ApprovalMode approvalMode) {
        delegate = new DefaultToolPolicyRequestAdapter(PRODUCT_ID, approvalMode);
    }

    @Override
    public PolicyRequest adapt(AgentRun run, FrozenToolBinding binding, ToolRequest request) {
        PolicyRequest baseline = delegate.adapt(run, binding, request);
        return applyCredentialBoundary(baseline, binding.definition().name().value(), request);
    }

    static PolicyRequest applyCredentialBoundary(PolicyRequest baseline, String definitionName, ToolRequest request) {
        Objects.requireNonNull(baseline, "baseline must not be null");
        Objects.requireNonNull(request, "request must not be null");
        if (!EXECUTION_RUN.equals(definitionName)) return baseline;
        Object value = request.arguments().values().get("command");
        if (!(value instanceof String command)) return baseline;
        CredentialEgressGuard.rejectionCode(command).ifPresent(code -> {
            throw new ToolAuthorizationProtocolException(
                    code, "Remove the host authentication override and use the managed Credential Lease path.");
        });
        return baseline;
    }
}

package io.haifa.agent.runtime.core.tool;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSideEffect;

/** Conservative default: reads are automatic; writes, execution, network and credentials require approval. */
public final class DefaultToolPolicy implements ToolPolicy {
    @Override
    public ToolPolicyDecision evaluate(AgentRun run, FrozenToolBinding binding, ToolRequest request) {
        var definition = binding.definition();
        if (definition.risk() == ToolRisk.CRITICAL
                && definition.approvalRequirement() == ToolApprovalRequirement.NEVER) {
            return ToolPolicyDecision.DENY;
        }
        if (definition.approvalRequirement() == ToolApprovalRequirement.REAUTHENTICATE) {
            return ToolPolicyDecision.REQUIRE_REAUTHENTICATION;
        }
        if (definition.approvalRequirement() == ToolApprovalRequirement.ALWAYS) {
            return ToolPolicyDecision.REQUIRE_APPROVAL;
        }
        if (definition.sideEffects().contains(ToolSideEffect.NETWORK_ACCESS)
                && definition.resources().networkHosts().isEmpty()) {
            return ToolPolicyDecision.DENY;
        }
        if (!definition.credentialRequirements().isEmpty()
                || definition.sideEffects().contains(ToolSideEffect.CREDENTIAL_USE)) {
            return ToolPolicyDecision.REQUIRE_REAUTHENTICATION;
        }
        if (definition.sideEffects().contains(ToolSideEffect.NETWORK_ACCESS)
                || definition.sideEffects().contains(ToolSideEffect.EXTERNAL_SYSTEM_MUTATION)) {
            return ToolPolicyDecision.REQUIRE_APPROVAL;
        }
        if (definition.sideEffects().contains(ToolSideEffect.PROCESS_EXECUTION)) {
            return ToolPolicyDecision.REQUIRE_APPROVAL;
        }
        if (definition.sideEffects().contains(ToolSideEffect.FILE_WRITE)) {
            return ToolPolicyDecision.REQUIRE_APPROVAL;
        }
        return definition.risk() == ToolRisk.HIGH ? ToolPolicyDecision.REQUIRE_APPROVAL : ToolPolicyDecision.ALLOW;
    }
}

package io.haifa.agent.runtime.core.interaction;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.policy.api.PolicyDecision;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.ToolArgumentsDigest;

/** Single construction rule for an ordinary one-shot Tool approval target. */
public final class ToolApprovalTargets {
    private ToolApprovalTargets() {}

    public static ToolApprovalTarget ordinary(
            AgentRun run,
            ToolCallId toolCallId,
            FrozenToolBinding binding,
            ToolRequest request,
            PolicyDecision decision) {
        return new ToolApprovalTarget(
                toolCallId,
                binding.coordinate().externalForm(),
                binding.coordinate().definitionHash().value(),
                ToolArgumentsDigest.sha256(request.arguments()),
                run.tenant().tenantId() + ":" + run.principal().principalType() + ":"
                        + run.principal().principalId(),
                decision.requirementDigest());
    }
}

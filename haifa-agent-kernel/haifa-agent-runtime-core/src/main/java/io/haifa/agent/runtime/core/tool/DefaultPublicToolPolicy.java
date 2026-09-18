package io.haifa.agent.runtime.core.tool;

import io.haifa.agent.policy.api.PolicyChallenge;
import io.haifa.agent.policy.api.PolicyDecision;
import io.haifa.agent.policy.api.PolicyDecisionService;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.policy.api.PolicyRequest;
import io.haifa.agent.policy.api.PolicyRequirementDigest;
import io.haifa.agent.policy.api.PolicyRuleSet;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.util.Objects;
import java.util.Optional;

/** Public-decision Tool adapter; hard contradictions fail closed before rule evaluation. */
public final class DefaultPublicToolPolicy implements PublicToolPolicy {
    private final ToolPolicyRequestAdapter requests;
    private final PolicyDecisionService decisions;
    private final PolicyRuleSet rules;

    public DefaultPublicToolPolicy(
            ToolPolicyRequestAdapter requests, PolicyDecisionService decisions, PolicyRuleSet rules) {
        this.requests = Objects.requireNonNull(requests, "requests must not be null");
        this.decisions = Objects.requireNonNull(decisions, "decisions must not be null");
        this.rules = Objects.requireNonNull(rules, "rules must not be null");
    }

    @Override
    public PolicyDecision evaluate(
            io.haifa.agent.core.run.AgentRun run,
            io.haifa.agent.tool.api.FrozenToolBinding binding,
            io.haifa.agent.runtime.core.decision.ToolRequest request) {
        PolicyRequest policyRequest = requests.adapt(run, binding, request);
        var definition = binding.definition();
        PolicyDecision decision;
        if (definition.risk() == ToolRisk.CRITICAL
                && definition.approvalRequirement() == ToolApprovalRequirement.NEVER) {
            decision = explicit(
                    policyRequest,
                    PolicyEffect.DENY,
                    Optional.empty(),
                    "TOOL_CRITICAL_NEVER_CONTRADICTION",
                    "Critical tools cannot disable approval");
        } else if (definition.sideEffects().contains(ToolSideEffect.NETWORK_ACCESS)
                && definition.resources().networkHosts().isEmpty()) {
            decision = explicit(
                    policyRequest,
                    PolicyEffect.DENY,
                    Optional.empty(),
                    "TOOL_NETWORK_TARGET_UNCONSTRAINED",
                    "Network tools require constrained target hosts");
        } else if (definition.approvalRequirement() == ToolApprovalRequirement.REAUTHENTICATE) {
            decision = explicit(
                    policyRequest,
                    rules.approvalMode() == io.haifa.agent.policy.api.ApprovalMode.DENY
                            ? PolicyEffect.DENY
                            : PolicyEffect.ASK,
                    rules.approvalMode() == io.haifa.agent.policy.api.ApprovalMode.DENY
                            ? Optional.empty()
                            : Optional.of(PolicyChallenge.REAUTHENTICATE),
                    "TOOL_REAUTHENTICATION_REQUIRED",
                    "Tool use requires reauthentication");
        } else if (definition.approvalRequirement() == ToolApprovalRequirement.ALWAYS) {
            PolicyEffect effect =
                    switch (rules.approvalMode()) {
                        case ASK -> PolicyEffect.ASK;
                        case AUTO -> PolicyEffect.ALLOW;
                        case DENY -> PolicyEffect.DENY;
                    };
            decision = explicit(
                    policyRequest,
                    effect,
                    effect == PolicyEffect.ASK ? Optional.of(PolicyChallenge.APPROVAL) : Optional.empty(),
                    "TOOL_APPROVAL_REQUIRED",
                    "Tool use requires confirmation");
        } else {
            decision = decisions.evaluate(policyRequest, rules);
        }
        return decision;
    }

    private PolicyDecision explicit(
            PolicyRequest request,
            PolicyEffect effect,
            Optional<PolicyChallenge> challenge,
            String reasonCode,
            String explanation) {
        return new PolicyDecision(
                effect, challenge, reasonCode, explanation, PolicyRequirementDigest.compute(request, rules));
    }
}

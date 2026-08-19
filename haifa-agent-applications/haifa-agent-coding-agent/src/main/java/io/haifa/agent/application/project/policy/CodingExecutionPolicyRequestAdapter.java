package io.haifa.agent.application.project.policy;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.execution.core.command.SystemGitCliCommandClassifier;
import io.haifa.agent.policy.api.ApprovalMode;
import io.haifa.agent.policy.api.PolicyContext;
import io.haifa.agent.policy.api.PolicyDigest;
import io.haifa.agent.policy.api.PolicyRequest;
import io.haifa.agent.policy.api.PolicyRisk;
import io.haifa.agent.policy.api.PolicySideEffect;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import io.haifa.agent.runtime.core.tool.DefaultToolPolicyRequestAdapter;
import io.haifa.agent.runtime.core.tool.ToolPolicyRequestAdapter;
import io.haifa.agent.tool.api.FrozenToolBinding;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/** Coding-owned execution risk resolver. The execution broker remains the authority for hard boundaries. */
public final class CodingExecutionPolicyRequestAdapter implements ToolPolicyRequestAdapter {
    static final String EXECUTION_RUN = "execution.run";
    private static final String PRODUCT_ID = "haifa-coding-agent";

    private final DefaultToolPolicyRequestAdapter delegate;

    public CodingExecutionPolicyRequestAdapter(ApprovalMode approvalMode) {
        delegate = new DefaultToolPolicyRequestAdapter(PRODUCT_ID, approvalMode);
    }

    @Override
    public PolicyRequest adapt(AgentRun run, FrozenToolBinding binding, ToolRequest request) {
        PolicyRequest baseline = delegate.adapt(run, binding, request);
        return withEffectiveExecutionRisk(baseline, binding.definition().name().value(), request);
    }

    static PolicyRequest withEffectiveExecutionRisk(
            PolicyRequest baseline, String definitionName, ToolRequest request) {
        if (!EXECUTION_RUN.equals(definitionName)) return baseline;
        Object value = request.arguments().values().get("command");
        if (!(value instanceof String command)) return baseline;

        var assessment =
                CodingExecutionRiskResolver.assess(command, baseline.risk().level());
        var classification = assessment.classification();
        PolicyRisk risk = resolveRisk(baseline.risk(), assessment);
        String resolverDigest = PolicyDigest.sha256Fields(List.of(
                "coding-execution-risk",
                CodingExecutionRiskResolver.VERSION,
                classification.target().name(),
                classification.risk().name(),
                classification.operation().name(),
                classification.reasonCode(),
                baseline.context().securityConfigurationDigest().orElse("")));
        PolicyContext original = baseline.context();
        PolicyContext context = new PolicyContext(
                original.projectRef(),
                original.sessionRef(),
                original.runRef(),
                original.attemptRef(),
                original.approvalMode(),
                original.projectTrustRef(),
                Optional.of(resolverDigest));
        return new PolicyRequest(baseline.subject(), context, baseline.action(), baseline.resource(), risk);
    }

    private static PolicyRisk resolveRisk(PolicyRisk baseline, CodingExecutionRiskResolver.Assessment assessment) {
        SystemGitCliCommandClassifier.Classification classification = assessment.classification();
        SystemGitCliCommandClassifier.Risk commandRisk = classification.risk();
        if (classification.target() == SystemGitCliCommandClassifier.Target.OTHER
                && commandRisk != SystemGitCliCommandClassifier.Risk.DENIED) {
            return baseline;
        }
        EnumSet<PolicySideEffect> sideEffects = baseline.sideEffects().isEmpty()
                ? EnumSet.noneOf(PolicySideEffect.class)
                : EnumSet.copyOf(baseline.sideEffects());
        switch (commandRisk) {
            case LOCAL_WRITE -> sideEffects.add(PolicySideEffect.FILE_WRITE);
            case NETWORK_READ -> sideEffects.add(PolicySideEffect.NETWORK_ACCESS);
            case EXTERNAL_WRITE -> {
                sideEffects.add(PolicySideEffect.NETWORK_ACCESS);
                sideEffects.add(PolicySideEffect.EXTERNAL_SYSTEM_MUTATION);
            }
            case DESTRUCTIVE, UNKNOWN, DENIED -> {
                sideEffects.add(PolicySideEffect.FILE_WRITE);
                sideEffects.add(PolicySideEffect.NETWORK_ACCESS);
                sideEffects.add(PolicySideEffect.EXTERNAL_SYSTEM_MUTATION);
            }
            case NOT_APPLICABLE, LOCAL_READ -> {
                // Preserve the static execution side effect and its configured baseline.
            }
        }
        return new PolicyRisk(
                assessment.effectiveRisk(),
                sideEffects,
                baseline.credentialRequired(),
                baseline.networkTargetSummary());
    }
}

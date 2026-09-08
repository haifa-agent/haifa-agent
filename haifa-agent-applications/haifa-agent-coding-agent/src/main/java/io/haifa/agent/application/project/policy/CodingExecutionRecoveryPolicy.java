package io.haifa.agent.application.project.policy;

import io.haifa.agent.application.project.tool.ProjectExecutionRecoveryAuthorization;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.policy.api.PolicyDecision;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import io.haifa.agent.runtime.core.tool.PublicToolPolicy;
import io.haifa.agent.tool.api.FrozenToolBinding;
import java.util.Objects;
import java.util.Optional;

/** Lets the already-approved deterministic recovery successor consume its one-shot Interaction. */
public final class CodingExecutionRecoveryPolicy implements PublicToolPolicy {
    private final PublicToolPolicy delegate;
    private final ProjectExecutionRecoveryAuthorization recovery;

    public CodingExecutionRecoveryPolicy(PublicToolPolicy delegate, ProjectExecutionRecoveryAuthorization recovery) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.recovery = Objects.requireNonNull(recovery, "recovery must not be null");
    }

    @Override
    public PolicyDecision evaluate(AgentRun run, FrozenToolBinding binding, ToolRequest request) {
        PolicyDecision decision = delegate.evaluate(run, binding, request);
        if (decision.effect() != PolicyEffect.ASK
                || !"execution.run".equals(binding.definition().name().value())
                || !recovery.isVerifiedSuccessor(
                        run.id(), request.toolCallId(), request.idempotencyKey().value(), request.arguments())) {
            return decision;
        }
        return new PolicyDecision(
                PolicyEffect.ALLOW,
                Optional.empty(),
                "EXECUTION_RECOVERY_APPROVED",
                "The exact deterministic execution recovery successor was approved",
                decision.requirementDigest());
    }
}

package io.haifa.agent.execution.core;

import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.policy.api.PolicyAuthorizationEvidenceStore;
import io.haifa.agent.policy.api.PolicyDecision;
import io.haifa.agent.policy.api.PolicyDecisionId;
import io.haifa.agent.policy.api.PolicyDecisionStore;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.policy.api.PolicySnapshotStore;
import java.time.Clock;
import java.util.Objects;

/**
 * Verifies the upstream public decision. This does not replace the Broker's capability,
 * workspace, profile, provider, sandbox, deadline, or output enforcement.
 */
public final class PolicyDecisionExecutionPolicy implements ExecutionPolicy {
    private final PolicyDecisionStore decisions;
    private final PolicySnapshotStore snapshots;
    private final PolicyAuthorizationEvidenceStore evidence;
    private final Clock clock;

    public PolicyDecisionExecutionPolicy(
            PolicyDecisionStore decisions,
            PolicySnapshotStore snapshots,
            PolicyAuthorizationEvidenceStore evidence,
            Clock clock) {
        this.decisions = Objects.requireNonNull(decisions, "decisions must not be null");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots must not be null");
        this.evidence = Objects.requireNonNull(evidence, "evidence must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public void authorize(ExecutionRequest request) {
        PolicyDecisionId id;
        try {
            id = new PolicyDecisionId(request.context().policyDecisionRef());
        } catch (IllegalArgumentException exception) {
            throw reject("POLICY_DECISION_REF_INVALID", "policy decision reference is invalid");
        }
        PolicyDecision decision = decisions
                .find(id)
                .orElseThrow(() -> reject("POLICY_DECISION_NOT_FOUND", "policy decision was not found"));
        var bound = decision.request()
                .orElseThrow(() -> reject("POLICY_DECISION_UNBOUND", "policy decision is not request-bound"));
        if (!snapshots.find(decision.snapshot()).isPresent()) {
            throw reject("POLICY_SNAPSHOT_NOT_FOUND", "policy snapshot was not found");
        }
        if (!bound.subject().tenant().equals(request.context().tenant())
                || !bound.subject().principal().equals(request.context().actor())) {
            throw reject("POLICY_SUBJECT_MISMATCH", "policy decision subject does not match execution");
        }
        if (bound.context().runRef().filter(request.context().runRef()::equals).isEmpty()) {
            throw reject("POLICY_RUN_MISMATCH", "policy decision run does not match execution");
        }
        if (!bound.action().capability().equals("execution.run")
                || !bound.action().operation().equals("invoke")) {
            throw reject("POLICY_ACTION_MISMATCH", "policy decision action does not match execution");
        }
        if (bound.resource()
                .resourceDigest()
                .filter(ExecutionPolicyBinding.resourceDigest(request)::equals)
                .isEmpty()) {
            throw reject("POLICY_RESOURCE_MISMATCH", "policy decision digest does not match execution");
        }
        if (decision.effect() == PolicyEffect.DENY) {
            throw reject("POLICY_DENIED", "policy denied execution");
        }
        if (decision.effect() == PolicyEffect.ASK) {
            var satisfied = evidence.find(decision.id())
                    .filter(value -> value.requestDigest().equals(decision.requestDigest()))
                    .filter(value ->
                            value.requester().tenant().equals(request.context().tenant()))
                    .filter(value -> value.requester()
                            .principal()
                            .equals(request.context().actor()))
                    .filter(value -> value.validAt(clock.instant()));
            if (satisfied.isEmpty()) {
                throw reject("POLICY_CHALLENGE_UNSATISFIED", "policy approval evidence is unavailable");
            }
        }
    }

    private static ExecutionRejectedException reject(String code, String detail) {
        return new ExecutionRejectedException(code, detail);
    }
}

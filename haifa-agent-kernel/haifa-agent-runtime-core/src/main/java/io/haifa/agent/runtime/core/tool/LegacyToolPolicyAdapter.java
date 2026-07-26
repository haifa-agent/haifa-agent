package io.haifa.agent.runtime.core.tool;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.policy.api.PolicyChallenge;
import io.haifa.agent.policy.api.PolicyDecision;
import io.haifa.agent.policy.api.PolicyDecisionId;
import io.haifa.agent.policy.api.PolicyDecisionStore;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.policy.api.PolicyRequest;
import io.haifa.agent.policy.api.PolicyRequestDigest;
import io.haifa.agent.policy.api.PolicySnapshotRef;
import java.util.Objects;
import java.util.Optional;

/**
 * Temporary source-compatibility adapter. It maps the legacy four-value result in one direction;
 * the Tool pipeline only consumes the resulting public decision.
 */
@Deprecated(forRemoval = true)
public final class LegacyToolPolicyAdapter implements PublicToolPolicy {
    private static final PolicySnapshotRef LEGACY_SNAPSHOT = new PolicySnapshotRef("legacy-tool-policy-v1");

    private final ToolPolicy legacy;
    private final ToolPolicyRequestAdapter requests;
    private final IdentifierGenerator ids;
    private final TimeProvider time;
    private final PolicyDecisionStore store;

    public LegacyToolPolicyAdapter(
            ToolPolicy legacy,
            ToolPolicyRequestAdapter requests,
            IdentifierGenerator ids,
            TimeProvider time,
            PolicyDecisionStore store) {
        this.legacy = Objects.requireNonNull(legacy, "legacy must not be null");
        this.requests = Objects.requireNonNull(requests, "requests must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    @Override
    public PolicyDecision evaluate(
            io.haifa.agent.core.run.AgentRun run,
            io.haifa.agent.tool.api.FrozenToolBinding binding,
            io.haifa.agent.runtime.core.decision.ToolRequest request) {
        PolicyRequest policyRequest = requests.adapt(run, binding, request);
        ToolPolicyDecision legacyDecision = legacy.evaluate(run, binding, request);
        PolicyEffect effect =
                switch (legacyDecision) {
                    case ALLOW -> PolicyEffect.ALLOW;
                    case REQUIRE_APPROVAL, REQUIRE_REAUTHENTICATION -> PolicyEffect.ASK;
                    case DENY -> PolicyEffect.DENY;
                };
        Optional<PolicyChallenge> challenge =
                switch (legacyDecision) {
                    case REQUIRE_APPROVAL -> Optional.of(PolicyChallenge.APPROVAL);
                    case REQUIRE_REAUTHENTICATION -> Optional.of(PolicyChallenge.REAUTHENTICATE);
                    case ALLOW, DENY -> Optional.empty();
                };
        PolicyDecision decision = new PolicyDecision(
                new PolicyDecisionId(ids.nextValue()),
                Optional.of(policyRequest),
                PolicyRequestDigest.compute(policyRequest),
                effect,
                challenge,
                "LEGACY_TOOL_POLICY_" + legacyDecision.name(),
                "Decision mapped from the deprecated Tool policy",
                LEGACY_SNAPSHOT,
                Optional.empty(),
                time.now());
        store.save(decision);
        return decision;
    }
}

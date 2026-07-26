package io.haifa.agent.runtime.core.policy;

import io.haifa.agent.policy.api.PolicyDecision;
import io.haifa.agent.policy.api.PolicyDecisionId;
import io.haifa.agent.policy.api.PolicyDecisionStore;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** In-memory compatibility store. Product applications should inject their shared Policy store. */
public final class RuntimePolicyDecisionStore implements PolicyDecisionStore {
    private final Map<PolicyDecisionId, PolicyDecision> decisions = new HashMap<>();

    @Override
    public synchronized void save(PolicyDecision decision) {
        PolicyDecision existing = decisions.putIfAbsent(decision.id(), decision);
        if (existing != null && !existing.equals(decision)) {
            throw new IllegalStateException("policy decision id is already used");
        }
    }

    @Override
    public synchronized Optional<PolicyDecision> find(PolicyDecisionId id) {
        return Optional.ofNullable(decisions.get(id));
    }
}

package io.haifa.agent.policy.core;

import io.haifa.agent.policy.api.PolicyAuthorizationEvidence;
import io.haifa.agent.policy.api.PolicyAuthorizationEvidenceStore;
import io.haifa.agent.policy.api.PolicyDecisionId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class InMemoryPolicyAuthorizationEvidenceStore implements PolicyAuthorizationEvidenceStore {
    private final Map<PolicyDecisionId, PolicyAuthorizationEvidence> evidence = new HashMap<>();

    @Override
    public synchronized void save(PolicyAuthorizationEvidence value) {
        PolicyAuthorizationEvidence existing = evidence.putIfAbsent(value.decisionId(), value);
        if (existing != null && !existing.equals(value)) {
            throw new IllegalStateException("policy authorization evidence already exists");
        }
    }

    @Override
    public synchronized Optional<PolicyAuthorizationEvidence> find(PolicyDecisionId decisionId) {
        return Optional.ofNullable(evidence.get(decisionId));
    }
}

package io.haifa.agent.runtime.core.policy;

import io.haifa.agent.policy.api.PolicyAuthorizationEvidence;
import io.haifa.agent.policy.api.PolicyAuthorizationEvidenceStore;
import io.haifa.agent.policy.api.PolicyDecisionId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** In-memory compatibility store. Durable applications replace it in Task 03. */
public final class RuntimePolicyAuthorizationEvidenceStore implements PolicyAuthorizationEvidenceStore {
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

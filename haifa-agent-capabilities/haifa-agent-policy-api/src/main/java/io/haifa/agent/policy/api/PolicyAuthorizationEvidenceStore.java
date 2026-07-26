package io.haifa.agent.policy.api;

import java.util.Optional;

public interface PolicyAuthorizationEvidenceStore {
    void save(PolicyAuthorizationEvidence evidence);

    Optional<PolicyAuthorizationEvidence> find(PolicyDecisionId decisionId);
}

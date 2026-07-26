package io.haifa.agent.policy.api;

import java.util.Optional;

public interface PolicyDecisionStore {
    void save(PolicyDecision decision);

    Optional<PolicyDecision> find(PolicyDecisionId id);
}

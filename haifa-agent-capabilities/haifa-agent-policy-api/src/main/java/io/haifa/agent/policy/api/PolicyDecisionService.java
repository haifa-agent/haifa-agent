package io.haifa.agent.policy.api;

public interface PolicyDecisionService {
    PolicyDecision evaluate(PolicyRequest request, PolicySnapshot snapshot);
}

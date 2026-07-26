package io.haifa.agent.policy.api;

@FunctionalInterface
public interface PolicyDecisionIdGenerator {
    PolicyDecisionId nextId();
}

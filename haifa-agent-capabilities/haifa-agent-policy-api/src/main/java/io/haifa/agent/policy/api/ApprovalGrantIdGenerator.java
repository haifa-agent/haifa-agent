package io.haifa.agent.policy.api;

/** Injectable approval-grant identity boundary. */
@FunctionalInterface
public interface ApprovalGrantIdGenerator {
    ApprovalGrantId nextId();
}

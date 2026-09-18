package io.haifa.agent.policy.api;

@FunctionalInterface
public interface ApprovalTargetValidator {
    ApprovalTargetValidation validateCurrent(ApprovalTargetRef target);
}

package io.haifa.agent.policy.api;

public interface ApprovalVerificationService {
    ApprovalVerification verify(ApprovalRequestContext request, ApprovalResponder responder);
}

package io.haifa.agent.policy.api;

public interface ApprovalVerificationService {
    ApprovalVerification verify(ApprovalRequester requester, ApprovalTargetRef target, ApprovalResponder responder);
}

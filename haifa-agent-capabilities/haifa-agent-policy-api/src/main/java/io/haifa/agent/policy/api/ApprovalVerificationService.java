package io.haifa.agent.policy.api;

public interface ApprovalVerificationService {
    ApprovalVerification verify(ApprovalRequestContext request, ApprovalResponder responder);

    default ApprovalVerification verifyLocal(
            ApprovalRequester requester, ApprovalTargetRef target, ApprovalResponder responder) {
        boolean samePrincipal = requester.tenant().equals(responder.tenant())
                && requester.principal().equals(responder.principal());
        return new ApprovalVerification(
                samePrincipal, samePrincipal ? "LOCAL_PRINCIPAL_MATCH" : "LOCAL_PRINCIPAL_MISMATCH");
    }
}

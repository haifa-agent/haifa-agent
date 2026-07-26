package io.haifa.agent.policy.api;

public interface ApprovalAuthorityVerifier {
    ApprovalAuthorityDecision verify(
            ApprovalAuthorityRequirementRef requirement,
            ApprovalRequester requester,
            ApprovalResponder responder,
            ApprovalTargetRef target);
}

package io.haifa.agent.policy.core;

import io.haifa.agent.policy.api.ApprovalAuthorityDecision;
import io.haifa.agent.policy.api.ApprovalAuthorityRequirementRef;
import io.haifa.agent.policy.api.ApprovalAuthorityStatus;
import io.haifa.agent.policy.api.ApprovalAuthorityVerifier;
import io.haifa.agent.policy.api.ApprovalRequester;
import io.haifa.agent.policy.api.ApprovalResponder;
import io.haifa.agent.policy.api.ApprovalTargetRef;

public final class LocalCapabilityAuthorityVerifier implements ApprovalAuthorityVerifier {
    public static final String PROVIDER_ID = "local.same-principal";

    @Override
    public ApprovalAuthorityDecision verify(
            ApprovalAuthorityRequirementRef requirement,
            ApprovalRequester requester,
            ApprovalResponder responder,
            ApprovalTargetRef target) {
        boolean eligible = requester.tenant().equals(responder.tenant())
                && requester.principal().equals(responder.principal());
        return eligible
                ? new ApprovalAuthorityDecision(ApprovalAuthorityStatus.ELIGIBLE, "LOCAL_PRINCIPAL_MATCH")
                : new ApprovalAuthorityDecision(ApprovalAuthorityStatus.INELIGIBLE, "LOCAL_PRINCIPAL_MISMATCH");
    }
}

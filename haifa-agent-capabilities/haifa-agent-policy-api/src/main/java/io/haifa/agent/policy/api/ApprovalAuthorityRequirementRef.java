package io.haifa.agent.policy.api;

import static io.haifa.agent.policy.api.PolicyValues.requireIdentifier;

public record ApprovalAuthorityRequirementRef(String providerId, String requirementId, String version) {
    public ApprovalAuthorityRequirementRef {
        providerId = requireIdentifier(providerId, "providerId");
        requirementId = requireIdentifier(requirementId, "requirementId");
        version = requireIdentifier(version, "version");
    }
}

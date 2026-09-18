package io.haifa.agent.policy.api;

import static io.haifa.agent.policy.api.PolicyValues.optionalIdentifier;
import static io.haifa.agent.policy.api.PolicyValues.requireIdentifier;
import static io.haifa.agent.policy.api.PolicyValues.requireSafeText;

import java.util.Optional;

public record PolicyResource(
        String resourceType, String resourceRef, Optional<String> resourceDigest, String safeSummary) {
    public PolicyResource {
        resourceType = requireIdentifier(resourceType, "resourceType");
        resourceRef = requireIdentifier(resourceRef, "resourceRef");
        resourceDigest = optionalIdentifier(resourceDigest, "resourceDigest");
        safeSummary = requireSafeText(safeSummary, "safeSummary");
    }
}

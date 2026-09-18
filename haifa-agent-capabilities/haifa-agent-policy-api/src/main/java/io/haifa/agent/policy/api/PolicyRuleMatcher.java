package io.haifa.agent.policy.api;

import static io.haifa.agent.policy.api.PolicyValues.optionalIdentifier;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record PolicyRuleMatcher(
        Optional<String> tenantId,
        Optional<String> productId,
        Optional<String> projectRef,
        Optional<String> sessionRef,
        Optional<String> capability,
        Optional<String> operation,
        Optional<String> resourceType,
        Optional<PolicyRiskLevel> minimumRisk,
        Set<PolicySideEffect> requiredSideEffects) {
    public PolicyRuleMatcher {
        tenantId = optionalIdentifier(tenantId, "tenantId");
        productId = optionalIdentifier(productId, "productId");
        projectRef = optionalIdentifier(projectRef, "projectRef");
        sessionRef = optionalIdentifier(sessionRef, "sessionRef");
        capability = optionalIdentifier(capability, "capability");
        operation = optionalIdentifier(operation, "operation");
        resourceType = optionalIdentifier(resourceType, "resourceType");
        minimumRisk = Objects.requireNonNull(minimumRisk, "minimumRisk must not be null");
        requiredSideEffects =
                Set.copyOf(Objects.requireNonNull(requiredSideEffects, "requiredSideEffects must not be null"));
    }

    public static PolicyRuleMatcher any() {
        return new PolicyRuleMatcher(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Set.of());
    }
}

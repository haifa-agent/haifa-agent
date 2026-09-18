package io.haifa.agent.policy.api;

import static io.haifa.agent.policy.api.PolicyValues.optionalSafeText;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record PolicyRisk(
        PolicyRiskLevel level,
        Set<PolicySideEffect> sideEffects,
        boolean credentialRequired,
        Optional<String> networkTargetSummary) {
    public PolicyRisk {
        level = Objects.requireNonNull(level, "level must not be null");
        sideEffects = Set.copyOf(Objects.requireNonNull(sideEffects, "sideEffects must not be null"));
        networkTargetSummary = optionalSafeText(networkTargetSummary, "networkTargetSummary");
    }
}

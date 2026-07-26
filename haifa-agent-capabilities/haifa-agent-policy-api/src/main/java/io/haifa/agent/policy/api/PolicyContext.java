package io.haifa.agent.policy.api;

import static io.haifa.agent.policy.api.PolicyValues.optionalIdentifier;

import java.util.Objects;
import java.util.Optional;

public record PolicyContext(
        Optional<String> projectRef,
        Optional<String> sessionRef,
        Optional<String> runRef,
        Optional<String> attemptRef,
        ApprovalMode approvalMode,
        Optional<ProjectTrustRef> projectTrustRef,
        Optional<String> securityConfigurationDigest) {
    public PolicyContext {
        projectRef = optionalIdentifier(projectRef, "projectRef");
        sessionRef = optionalIdentifier(sessionRef, "sessionRef");
        runRef = optionalIdentifier(runRef, "runRef");
        attemptRef = optionalIdentifier(attemptRef, "attemptRef");
        approvalMode = Objects.requireNonNull(approvalMode, "approvalMode must not be null");
        projectTrustRef = Objects.requireNonNull(projectTrustRef, "projectTrustRef must not be null");
        securityConfigurationDigest = optionalIdentifier(securityConfigurationDigest, "securityConfigurationDigest");
    }

    public static PolicyContext run(String runRef, ApprovalMode approvalMode) {
        return new PolicyContext(
                Optional.empty(),
                Optional.empty(),
                Optional.of(runRef),
                Optional.empty(),
                approvalMode,
                Optional.empty(),
                Optional.empty());
    }
}

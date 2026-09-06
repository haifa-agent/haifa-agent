package io.haifa.agent.policy.api;

import java.util.Objects;
import java.util.Optional;

/** Public authorization/grant service shared by product assemblies and Runtime. */
public interface PolicyAuthorizationService {
    AuthorizationResult authorize(
            PolicyDecision decision, ApprovalTargetRef target, Optional<ProjectTrustExpectation> projectExpectation);

    ApprovalGrant createGrant(ApprovalGrantCreationRequest request);

    ApprovalGrantRevocation revoke(ApprovalGrantId id, long expectedVersion, String reasonCode);

    default boolean persistentGrantsEnabled() {
        return true;
    }

    /** Compatibility mode for applications that have not configured persistent grants. */
    static PolicyAuthorizationService decisionOnly() {
        return new PolicyAuthorizationService() {
            @Override
            public AuthorizationResult authorize(
                    PolicyDecision decision,
                    ApprovalTargetRef target,
                    Optional<ProjectTrustExpectation> projectExpectation) {
                Objects.requireNonNull(target, "target must not be null");
                Objects.requireNonNull(projectExpectation, "projectExpectation must not be null");
                return AuthorizationResult.fromPolicy(decision);
            }

            @Override
            public ApprovalGrant createGrant(ApprovalGrantCreationRequest request) {
                throw new IllegalStateException("persistent approval grants are not configured");
            }

            @Override
            public ApprovalGrantRevocation revoke(ApprovalGrantId id, long expectedVersion, String reasonCode) {
                throw new IllegalStateException("persistent approval grants are not configured");
            }

            @Override
            public boolean persistentGrantsEnabled() {
                return false;
            }
        };
    }
}

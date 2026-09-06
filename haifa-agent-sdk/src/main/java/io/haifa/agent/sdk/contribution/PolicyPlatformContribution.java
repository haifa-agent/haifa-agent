package io.haifa.agent.sdk.contribution;

import io.haifa.agent.policy.api.ApprovalGrantStore;
import io.haifa.agent.policy.api.PolicyAuthorizationEvidenceStore;
import io.haifa.agent.policy.api.PolicyAuthorizationService;
import io.haifa.agent.policy.api.PolicyDecisionStore;
import io.haifa.agent.policy.api.PolicySnapshotStore;
import io.haifa.agent.policy.api.ProjectTrustStore;
import io.haifa.agent.sdk.product.ProductCapabilities;
import java.util.Objects;
import java.util.Optional;

/** Product-selected durable Policy decision and authorization-evidence stores. */
public final class PolicyPlatformContribution extends AbstractSdkContribution {
    private final PolicyDecisionStore decisions;
    private final PolicyAuthorizationEvidenceStore authorizationEvidence;
    private final PolicySnapshotStore snapshots;
    private final Optional<ApprovalGrantStore> approvalGrants;
    private final Optional<ProjectTrustStore> projectTrusts;
    private final PolicyAuthorizationService authorization;

    public PolicyPlatformContribution(
            SdkContributionMetadata metadata,
            PolicyDecisionStore decisions,
            PolicyAuthorizationEvidenceStore authorizationEvidence) {
        this(metadata, inMemorySnapshots(), decisions, authorizationEvidence);
    }

    public PolicyPlatformContribution(
            SdkContributionMetadata metadata,
            PolicySnapshotStore snapshots,
            PolicyDecisionStore decisions,
            PolicyAuthorizationEvidenceStore authorizationEvidence) {
        this(
                metadata,
                snapshots,
                decisions,
                authorizationEvidence,
                Optional.empty(),
                Optional.empty(),
                PolicyAuthorizationService.decisionOnly());
    }

    public PolicyPlatformContribution(
            SdkContributionMetadata metadata,
            PolicySnapshotStore snapshots,
            PolicyDecisionStore decisions,
            PolicyAuthorizationEvidenceStore authorizationEvidence,
            ApprovalGrantStore approvalGrants,
            ProjectTrustStore projectTrusts) {
        this(
                metadata,
                snapshots,
                decisions,
                authorizationEvidence,
                Optional.of(Objects.requireNonNull(approvalGrants, "approvalGrants must not be null")),
                Optional.of(Objects.requireNonNull(projectTrusts, "projectTrusts must not be null")),
                PolicyAuthorizationService.decisionOnly());
    }

    private PolicyPlatformContribution(
            SdkContributionMetadata metadata,
            PolicySnapshotStore snapshots,
            PolicyDecisionStore decisions,
            PolicyAuthorizationEvidenceStore authorizationEvidence,
            Optional<ApprovalGrantStore> approvalGrants,
            Optional<ProjectTrustStore> projectTrusts,
            PolicyAuthorizationService authorization) {
        super(metadata);
        if (!ProductCapabilities.POLICY.equals(metadata.capabilityId())) {
            throw new IllegalArgumentException("policy contribution must provide the policy capability");
        }
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots must not be null");
        this.decisions = Objects.requireNonNull(decisions, "decisions must not be null");
        this.authorizationEvidence =
                Objects.requireNonNull(authorizationEvidence, "authorizationEvidence must not be null");
        this.approvalGrants = Objects.requireNonNull(approvalGrants, "approvalGrants must not be null");
        this.projectTrusts = Objects.requireNonNull(projectTrusts, "projectTrusts must not be null");
        this.authorization = Objects.requireNonNull(authorization, "authorization must not be null");
    }

    public PolicySnapshotStore snapshots() {
        return snapshots;
    }

    private static PolicySnapshotStore inMemorySnapshots() {
        var values = new java.util.concurrent.ConcurrentHashMap<
                io.haifa.agent.policy.api.PolicySnapshotRef, io.haifa.agent.policy.api.PolicySnapshot>();
        return new PolicySnapshotStore() {
            @Override
            public void save(io.haifa.agent.policy.api.PolicySnapshot snapshot) {
                var previous = values.putIfAbsent(snapshot.ref(), snapshot);
                if (previous != null && !previous.equals(snapshot)) {
                    throw new IllegalStateException("policy snapshot reference is already used");
                }
            }

            @Override
            public java.util.Optional<io.haifa.agent.policy.api.PolicySnapshot> find(
                    io.haifa.agent.policy.api.PolicySnapshotRef ref) {
                return java.util.Optional.ofNullable(values.get(ref));
            }
        };
    }

    public PolicyDecisionStore decisions() {
        return decisions;
    }

    public PolicyAuthorizationEvidenceStore authorizationEvidence() {
        return authorizationEvidence;
    }

    public Optional<ApprovalGrantStore> approvalGrants() {
        return approvalGrants;
    }

    public Optional<ProjectTrustStore> projectTrusts() {
        return projectTrusts;
    }

    public PolicyAuthorizationService authorization() {
        return authorization;
    }

    public PolicyPlatformContribution withAuthorization(PolicyAuthorizationService value) {
        return new PolicyPlatformContribution(
                new SdkContributionMetadata(
                        coordinate(), capabilityId(), configurationDigest(), suitability(), publicSummary()),
                snapshots,
                decisions,
                authorizationEvidence,
                approvalGrants,
                projectTrusts,
                value);
    }
}

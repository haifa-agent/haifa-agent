package io.haifa.agent.sdk.contribution;

import io.haifa.agent.policy.api.PolicyAuthorizationEvidenceStore;
import io.haifa.agent.policy.api.PolicyDecisionStore;
import io.haifa.agent.policy.api.PolicySnapshotStore;
import io.haifa.agent.sdk.product.ProductCapabilities;
import java.util.Objects;

/** Product-selected durable Policy decision and authorization-evidence stores. */
public final class PolicyPlatformContribution extends AbstractSdkContribution {
    private final PolicyDecisionStore decisions;
    private final PolicyAuthorizationEvidenceStore authorizationEvidence;
    private final PolicySnapshotStore snapshots;

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
        super(metadata);
        if (!ProductCapabilities.POLICY.equals(metadata.capabilityId())) {
            throw new IllegalArgumentException("policy contribution must provide the policy capability");
        }
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots must not be null");
        this.decisions = Objects.requireNonNull(decisions, "decisions must not be null");
        this.authorizationEvidence =
                Objects.requireNonNull(authorizationEvidence, "authorizationEvidence must not be null");
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
}

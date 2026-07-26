package io.haifa.agent.policy.api;

import java.util.Objects;

/** Product-neutral authoritative stores used by Runtime and enforcement adapters. */
public record PolicyPersistencePorts(
        PolicySnapshotStore snapshots,
        PolicyDecisionStore decisions,
        PolicyAuthorizationEvidenceStore authorizationEvidence,
        ApprovalGrantStore grants,
        ProjectTrustStore projectTrusts) {
    public PolicyPersistencePorts {
        snapshots = Objects.requireNonNull(snapshots, "snapshots must not be null");
        decisions = Objects.requireNonNull(decisions, "decisions must not be null");
        authorizationEvidence = Objects.requireNonNull(authorizationEvidence, "authorizationEvidence must not be null");
        grants = Objects.requireNonNull(grants, "grants must not be null");
        projectTrusts = Objects.requireNonNull(projectTrusts, "projectTrusts must not be null");
    }
}

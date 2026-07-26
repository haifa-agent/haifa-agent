package io.haifa.agent.policy.api;

import static io.haifa.agent.policy.api.PolicyValues.requireIdentifier;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record PolicySnapshot(
        PolicySnapshotRef ref,
        List<PolicyRule> rules,
        Optional<PolicyRule> defaultRule,
        ApprovalMode approvalMode,
        String productProfileRef,
        Optional<ProjectTrustRef> projectTrustRef,
        String contentDigest,
        Instant createdAt) {
    public PolicySnapshot {
        ref = Objects.requireNonNull(ref, "ref must not be null");
        rules = List.copyOf(Objects.requireNonNull(rules, "rules must not be null"));
        defaultRule = Objects.requireNonNull(defaultRule, "defaultRule must not be null");
        approvalMode = Objects.requireNonNull(approvalMode, "approvalMode must not be null");
        productProfileRef = requireIdentifier(productProfileRef, "productProfileRef");
        projectTrustRef = Objects.requireNonNull(projectTrustRef, "projectTrustRef must not be null");
        contentDigest = requireIdentifier(contentDigest, "contentDigest");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}

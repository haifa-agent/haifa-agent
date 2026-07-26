package io.haifa.agent.policy.api;

import static io.haifa.agent.policy.api.PolicyValues.optionalIdentifier;
import static io.haifa.agent.policy.api.PolicyValues.requireIdentifier;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record ApprovalGrant(
        ApprovalGrantId id,
        ApprovalSemantics semantics,
        ApprovalReuseScope reuseScope,
        PolicySubject subject,
        PolicyAction action,
        ApprovalTargetRef target,
        Optional<String> sessionRef,
        Optional<String> projectRef,
        Optional<ProjectTrustRef> projectTrustRef,
        Optional<String> securityConfigurationDigest,
        PolicyDecisionId sourceDecisionId,
        String sourceApprovalRequestRef,
        ApprovalResponder createdBy,
        Instant createdAt,
        Optional<Instant> expiresAt,
        ApprovalGrantState state,
        Optional<Instant> revokedAt,
        Optional<Instant> consumedAt,
        long version) {
    public ApprovalGrant {
        id = Objects.requireNonNull(id, "id must not be null");
        semantics = Objects.requireNonNull(semantics, "semantics must not be null");
        reuseScope = Objects.requireNonNull(reuseScope, "reuseScope must not be null");
        subject = Objects.requireNonNull(subject, "subject must not be null");
        action = Objects.requireNonNull(action, "action must not be null");
        target = Objects.requireNonNull(target, "target must not be null");
        sessionRef = optionalIdentifier(sessionRef, "sessionRef");
        projectRef = optionalIdentifier(projectRef, "projectRef");
        projectTrustRef = Objects.requireNonNull(projectTrustRef, "projectTrustRef must not be null");
        securityConfigurationDigest = optionalIdentifier(securityConfigurationDigest, "securityConfigurationDigest");
        sourceDecisionId = Objects.requireNonNull(sourceDecisionId, "sourceDecisionId must not be null");
        sourceApprovalRequestRef = requireIdentifier(sourceApprovalRequestRef, "sourceApprovalRequestRef");
        createdBy = Objects.requireNonNull(createdBy, "createdBy must not be null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        state = Objects.requireNonNull(state, "state must not be null");
        revokedAt = Objects.requireNonNull(revokedAt, "revokedAt must not be null");
        consumedAt = Objects.requireNonNull(consumedAt, "consumedAt must not be null");
        if (semantics != ApprovalSemantics.CAPABILITY_CONFIRMATION) {
            throw new IllegalArgumentException("business authorization cannot create a reusable grant");
        }
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
        if (expiresAt.isPresent() && !expiresAt.orElseThrow().isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
        validateScope(reuseScope, sessionRef, projectRef, projectTrustRef, securityConfigurationDigest);
        validateState(state, revokedAt, consumedAt);
    }

    public boolean activeAt(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return state == ApprovalGrantState.ACTIVE
                && expiresAt.map(expiration -> now.isBefore(expiration)).orElse(true);
    }

    public ApprovalGrant consume(Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        if (reuseScope != ApprovalReuseScope.ONCE || !activeAt(at)) {
            throw new IllegalStateException("grant cannot be consumed");
        }
        return new ApprovalGrant(
                id,
                semantics,
                reuseScope,
                subject,
                action,
                target,
                sessionRef,
                projectRef,
                projectTrustRef,
                securityConfigurationDigest,
                sourceDecisionId,
                sourceApprovalRequestRef,
                createdBy,
                createdAt,
                expiresAt,
                ApprovalGrantState.CONSUMED,
                Optional.empty(),
                Optional.of(at),
                version + 1);
    }

    public ApprovalGrant revoke(Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        if (state != ApprovalGrantState.ACTIVE) throw new IllegalStateException("only active grant can be revoked");
        return new ApprovalGrant(
                id,
                semantics,
                reuseScope,
                subject,
                action,
                target,
                sessionRef,
                projectRef,
                projectTrustRef,
                securityConfigurationDigest,
                sourceDecisionId,
                sourceApprovalRequestRef,
                createdBy,
                createdAt,
                expiresAt,
                ApprovalGrantState.REVOKED,
                Optional.of(at),
                Optional.empty(),
                version + 1);
    }

    private static void validateScope(
            ApprovalReuseScope scope,
            Optional<String> sessionRef,
            Optional<String> projectRef,
            Optional<ProjectTrustRef> projectTrustRef,
            Optional<String> securityConfigurationDigest) {
        if (scope == ApprovalReuseScope.SESSION && sessionRef.isEmpty()) {
            throw new IllegalArgumentException("SESSION grant requires sessionRef");
        }
        if (scope == ApprovalReuseScope.PROJECT
                && (projectRef.isEmpty() || projectTrustRef.isEmpty() || securityConfigurationDigest.isEmpty())) {
            throw new IllegalArgumentException("PROJECT grant requires project trust and configuration digest");
        }
    }

    private static void validateState(
            ApprovalGrantState state, Optional<Instant> revokedAt, Optional<Instant> consumedAt) {
        if (state == ApprovalGrantState.ACTIVE && (revokedAt.isPresent() || consumedAt.isPresent())) {
            throw new IllegalArgumentException("active grant cannot have terminal timestamps");
        }
        if (state == ApprovalGrantState.REVOKED && (revokedAt.isEmpty() || consumedAt.isPresent())) {
            throw new IllegalArgumentException("revoked grant requires revokedAt only");
        }
        if (state == ApprovalGrantState.CONSUMED && (consumedAt.isEmpty() || revokedAt.isPresent())) {
            throw new IllegalArgumentException("consumed grant requires consumedAt only");
        }
    }
}

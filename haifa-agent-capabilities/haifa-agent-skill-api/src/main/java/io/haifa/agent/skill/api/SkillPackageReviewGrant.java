package io.haifa.agent.skill.api;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Exact authorization for a reviewed package to enter the effective Skill catalog. */
public record SkillPackageReviewGrant(
        String id,
        int schemaVersion,
        long version,
        TenantRef tenant,
        PrincipalRef principal,
        String productId,
        SkillTrustScope scope,
        Optional<String> projectRef,
        SkillCoordinate coordinate,
        SkillContentDigest registrationDigest,
        SkillContentDigest packageDigest,
        Instant issuedAt,
        Optional<Instant> expiresAt,
        Optional<Instant> revokedAt,
        SkillTrustGrantState state,
        String reviewerRef,
        String reviewSourceRef,
        String reasonCode) {
    public SkillPackageReviewGrant {
        id = SkillValues.text(id, "id", 128);
        if (schemaVersion != 1) throw new IllegalArgumentException("unsupported package grant schemaVersion");
        if (version < 1) throw new IllegalArgumentException("version must be positive");
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        principal = Objects.requireNonNull(principal, "principal must not be null");
        productId = SkillValues.text(productId, "productId", 128);
        scope = Objects.requireNonNull(scope, "scope must not be null");
        projectRef = Objects.requireNonNull(projectRef, "projectRef must not be null")
                .map(value -> SkillValues.text(value, "projectRef", 256));
        coordinate = Objects.requireNonNull(coordinate, "coordinate must not be null");
        registrationDigest = Objects.requireNonNull(registrationDigest, "registrationDigest must not be null");
        packageDigest = Objects.requireNonNull(packageDigest, "packageDigest must not be null");
        issuedAt = Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        revokedAt = Objects.requireNonNull(revokedAt, "revokedAt must not be null");
        state = Objects.requireNonNull(state, "state must not be null");
        reviewerRef = SkillValues.text(reviewerRef, "reviewerRef", 256);
        reviewSourceRef = SkillValues.text(reviewSourceRef, "reviewSourceRef", 256);
        reasonCode = SkillValues.text(reasonCode, "reasonCode", 128);
        if (!coordinate.contentDigest().equals(packageDigest)) {
            throw new IllegalArgumentException("coordinate and package digests differ");
        }
        if (expiresAt.isPresent() && !expiresAt.orElseThrow().isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt");
        }
        if ((state == SkillTrustGrantState.REVOKED) != revokedAt.isPresent()) {
            throw new IllegalArgumentException("revoked state and revokedAt must agree");
        }
        if (revokedAt.isPresent() && revokedAt.orElseThrow().isBefore(issuedAt)) {
            throw new IllegalArgumentException("revokedAt must not precede issuedAt");
        }
        if (scope == SkillTrustScope.PROJECT && projectRef.isEmpty()) {
            throw new IllegalArgumentException("project scope requires projectRef");
        }
        if (scope != SkillTrustScope.PROJECT && projectRef.isPresent()) {
            throw new IllegalArgumentException("only project scope may carry projectRef");
        }
    }

    public boolean activeAt(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return state == SkillTrustGrantState.ACTIVE
                && revokedAt.isEmpty()
                && !now.isBefore(issuedAt)
                && expiresAt.map(now::isBefore).orElse(true);
    }

    public boolean matches(SkillRegistration registration, SkillTrustSubject subject, Instant now) {
        Objects.requireNonNull(registration, "registration must not be null");
        return activeAt(now)
                && matchesSubject(subject)
                && coordinate.equals(registration.coordinate())
                && registrationDigest.equals(registration.registrationDigest())
                && packageDigest.equals(registration.packageIndex().digest());
    }

    public boolean matches(FrozenSkillBinding binding, SkillTrustSubject subject, Instant now) {
        Objects.requireNonNull(binding, "binding must not be null");
        return activeAt(now)
                && matchesSubject(subject)
                && coordinate.equals(binding.coordinate())
                && registrationDigest.equals(binding.registrationDigest())
                && packageDigest.equals(binding.resourceIndexDigest());
    }

    private boolean matchesSubject(SkillTrustSubject subject) {
        Objects.requireNonNull(subject, "subject must not be null");
        return tenant.equals(subject.tenant())
                && principal.equals(subject.principal())
                && productId.equals(subject.productId())
                && (scope != SkillTrustScope.PROJECT || projectRef.equals(subject.projectRef()));
    }
}

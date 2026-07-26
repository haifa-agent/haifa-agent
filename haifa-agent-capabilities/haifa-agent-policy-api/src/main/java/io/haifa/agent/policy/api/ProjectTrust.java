package io.haifa.agent.policy.api;

import static io.haifa.agent.policy.api.PolicyValues.requireIdentifier;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record ProjectTrust(
        ProjectTrustRef ref,
        TenantRef tenant,
        PrincipalRef principal,
        String projectRef,
        String canonicalProjectIdentity,
        String trustedRootIdentity,
        String securityConfigurationDigest,
        String productProfileRef,
        ProjectTrustState state,
        Instant confirmedAt,
        Optional<Instant> expiresAt,
        Optional<Instant> revokedAt,
        long version) {
    public ProjectTrust {
        ref = Objects.requireNonNull(ref, "ref must not be null");
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        principal = Objects.requireNonNull(principal, "principal must not be null");
        projectRef = requireIdentifier(projectRef, "projectRef");
        canonicalProjectIdentity = requireIdentifier(canonicalProjectIdentity, "canonicalProjectIdentity");
        trustedRootIdentity = requireIdentifier(trustedRootIdentity, "trustedRootIdentity");
        securityConfigurationDigest = requireIdentifier(securityConfigurationDigest, "securityConfigurationDigest");
        productProfileRef = requireIdentifier(productProfileRef, "productProfileRef");
        state = Objects.requireNonNull(state, "state must not be null");
        confirmedAt = Objects.requireNonNull(confirmedAt, "confirmedAt must not be null");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        revokedAt = Objects.requireNonNull(revokedAt, "revokedAt must not be null");
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
        if (expiresAt.isPresent() && !expiresAt.orElseThrow().isAfter(confirmedAt)) {
            throw new IllegalArgumentException("expiresAt must be after confirmedAt");
        }
        if (state == ProjectTrustState.TRUSTED && revokedAt.isPresent()) {
            throw new IllegalArgumentException("trusted project cannot have revokedAt");
        }
        if (state == ProjectTrustState.REVOKED && revokedAt.isEmpty()) {
            throw new IllegalArgumentException("revoked project requires revokedAt");
        }
    }

    public boolean matches(
            TenantRef tenant,
            PrincipalRef principal,
            String projectRef,
            String canonicalProjectIdentity,
            String trustedRootIdentity,
            String securityConfigurationDigest,
            String productProfileRef,
            Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return state == ProjectTrustState.TRUSTED
                && expiresAt.map(expiration -> now.isBefore(expiration)).orElse(true)
                && this.tenant.equals(tenant)
                && this.principal.equals(principal)
                && this.projectRef.equals(projectRef)
                && this.canonicalProjectIdentity.equals(canonicalProjectIdentity)
                && this.trustedRootIdentity.equals(trustedRootIdentity)
                && this.securityConfigurationDigest.equals(securityConfigurationDigest)
                && this.productProfileRef.equals(productProfileRef);
    }

    public ProjectTrust revoke(Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        if (state != ProjectTrustState.TRUSTED) throw new IllegalStateException("project trust is already revoked");
        return new ProjectTrust(
                ref,
                tenant,
                principal,
                projectRef,
                canonicalProjectIdentity,
                trustedRootIdentity,
                securityConfigurationDigest,
                productProfileRef,
                ProjectTrustState.REVOKED,
                confirmedAt,
                expiresAt,
                Optional.of(at),
                version + 1);
    }
}

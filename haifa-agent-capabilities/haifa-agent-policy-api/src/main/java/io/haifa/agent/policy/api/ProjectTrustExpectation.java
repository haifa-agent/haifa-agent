package io.haifa.agent.policy.api;

import static io.haifa.agent.policy.api.PolicyValues.requireIdentifier;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import java.util.Objects;

/**
 * Current product-owned project identity and authorization configuration presented for trust
 * validation.
 */
public record ProjectTrustExpectation(
        TenantRef tenant,
        PrincipalRef principal,
        String projectRef,
        String canonicalProjectIdentity,
        String trustedRootIdentity,
        String securityConfigurationDigest,
        String productProfileRef) {
    public ProjectTrustExpectation {
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        principal = Objects.requireNonNull(principal, "principal must not be null");
        projectRef = requireIdentifier(projectRef, "projectRef");
        canonicalProjectIdentity = requireIdentifier(canonicalProjectIdentity, "canonicalProjectIdentity");
        trustedRootIdentity = requireIdentifier(trustedRootIdentity, "trustedRootIdentity");
        securityConfigurationDigest = requireIdentifier(securityConfigurationDigest, "securityConfigurationDigest");
        productProfileRef = requireIdentifier(productProfileRef, "productProfileRef");
    }
}

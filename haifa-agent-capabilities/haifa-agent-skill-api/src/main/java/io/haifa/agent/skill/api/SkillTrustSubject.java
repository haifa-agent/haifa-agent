package io.haifa.agent.skill.api;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import java.util.Objects;
import java.util.Optional;

/** Trusted caller identity used to evaluate a Skill trust manifest. */
public record SkillTrustSubject(
        TenantRef tenant, PrincipalRef principal, String productId, Optional<String> projectRef) {
    public SkillTrustSubject {
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        principal = Objects.requireNonNull(principal, "principal must not be null");
        productId = SkillValues.text(productId, "productId", 128);
        projectRef = Objects.requireNonNull(projectRef, "projectRef must not be null")
                .map(value -> SkillValues.text(value, "projectRef", 256));
    }
}

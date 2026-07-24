package io.haifa.agent.skill.api;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.reference.TenantRef;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record SkillVisibilityContext(
        TenantRef tenant,
        PrincipalRef principal,
        Optional<ProjectRef> project,
        boolean projectTrusted,
        Set<SkillScope> allowedScopes) {
    public SkillVisibilityContext {
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        principal = Objects.requireNonNull(principal, "principal must not be null");
        project = Objects.requireNonNull(project, "project must not be null");
        allowedScopes = Set.copyOf(Objects.requireNonNull(allowedScopes, "allowedScopes must not be null"));
        if (projectTrusted && project.isEmpty()) {
            throw new IllegalArgumentException("projectTrusted requires a project");
        }
    }
}

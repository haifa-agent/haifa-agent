package io.haifa.agent.skill.api;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.reference.TenantRef;
import java.util.Objects;
import java.util.Optional;

public record SkillScopeRef(
        SkillScope scope, Optional<TenantRef> tenant, Optional<PrincipalRef> owner, Optional<ProjectRef> project) {
    public SkillScopeRef {
        scope = Objects.requireNonNull(scope, "scope must not be null");
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        owner = Objects.requireNonNull(owner, "owner must not be null");
        project = Objects.requireNonNull(project, "project must not be null");
        switch (scope) {
            case SDK, PRODUCT -> require(tenant.isEmpty() && owner.isEmpty() && project.isEmpty(), scope);
            case TENANT -> require(tenant.isPresent() && owner.isEmpty() && project.isEmpty(), scope);
            case USER -> require(tenant.isPresent() && owner.isPresent() && project.isEmpty(), scope);
            case PROJECT -> require(tenant.isPresent() && owner.isEmpty() && project.isPresent(), scope);
        }
    }

    public static SkillScopeRef sdk() {
        return new SkillScopeRef(SkillScope.SDK, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static SkillScopeRef product() {
        return new SkillScopeRef(SkillScope.PRODUCT, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static SkillScopeRef tenant(TenantRef tenant) {
        return new SkillScopeRef(SkillScope.TENANT, Optional.of(tenant), Optional.empty(), Optional.empty());
    }

    public static SkillScopeRef user(TenantRef tenant, PrincipalRef owner) {
        return new SkillScopeRef(SkillScope.USER, Optional.of(tenant), Optional.of(owner), Optional.empty());
    }

    public static SkillScopeRef project(TenantRef tenant, ProjectRef project) {
        return new SkillScopeRef(SkillScope.PROJECT, Optional.of(tenant), Optional.empty(), Optional.of(project));
    }

    public boolean visibleTo(SkillVisibilityContext context) {
        Objects.requireNonNull(context, "context must not be null");
        if (!context.allowedScopes().contains(scope)) return false;
        return switch (scope) {
            case SDK, PRODUCT -> true;
            case TENANT -> tenant.orElseThrow().equals(context.tenant());
            case USER ->
                tenant.orElseThrow().equals(context.tenant())
                        && owner.orElseThrow().equals(context.principal());
            case PROJECT ->
                context.projectTrusted()
                        && tenant.orElseThrow().equals(context.tenant())
                        && context.project()
                                .filter(project.orElseThrow()::equals)
                                .isPresent();
        };
    }

    public String externalForm() {
        return switch (scope) {
            case SDK, PRODUCT -> scope.name().toLowerCase(java.util.Locale.ROOT);
            case TENANT -> "tenant:" + tenant.orElseThrow().tenantId();
            case USER ->
                "user:" + tenant.orElseThrow().tenantId() + ":"
                        + owner.orElseThrow().principalId();
            case PROJECT ->
                "project:" + tenant.orElseThrow().tenantId() + ":"
                        + project.orElseThrow().projectId();
        };
    }

    private static void require(boolean valid, SkillScope scope) {
        if (!valid) throw new IllegalArgumentException("invalid owner references for " + scope + " skill scope");
    }
}

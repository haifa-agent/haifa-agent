package io.haifa.agent.skill.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.reference.TenantRef;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SkillValueTest {
    private static final TenantRef TENANT = new TenantRef("tenant-a");
    private static final PrincipalRef USER = new PrincipalRef("user-a", "human");

    @Test
    void validatesPortableNamesAndDigests() {
        assertThrows(IllegalArgumentException.class, () -> new SkillName("Bad_Name"));
        assertThrows(IllegalArgumentException.class, () -> new SkillName("bad--name"));
        assertThrows(IllegalArgumentException.class, () -> new SkillContentDigest("sha256:bad"));
        new SkillDeclaredVersion("1.2.3-beta.1");
    }

    @Test
    void filtersOwnerScopesBeforeDisclosure() {
        var context = new SkillVisibilityContext(
                TENANT,
                USER,
                Optional.of(new ProjectRef("project-a")),
                true,
                Set.of(SkillScope.SDK, SkillScope.TENANT, SkillScope.USER, SkillScope.PROJECT));
        assertTrue(SkillScopeRef.sdk().visibleTo(context));
        assertTrue(SkillScopeRef.tenant(TENANT).visibleTo(context));
        assertTrue(SkillScopeRef.user(TENANT, USER).visibleTo(context));
        assertTrue(SkillScopeRef.project(TENANT, new ProjectRef("project-a")).visibleTo(context));
        assertFalse(SkillScopeRef.tenant(new TenantRef("tenant-b")).visibleTo(context));
        assertFalse(
                SkillScopeRef.user(TENANT, new PrincipalRef("user-b", "human")).visibleTo(context));
        assertFalse(SkillScopeRef.project(TENANT, new ProjectRef("project-b")).visibleTo(context));

        var untrustedProject = new SkillVisibilityContext(
                TENANT, USER, Optional.of(new ProjectRef("project-a")), false, Set.of(SkillScope.PROJECT));
        assertFalse(SkillScopeRef.project(TENANT, new ProjectRef("project-a")).visibleTo(untrustedProject));
    }
}

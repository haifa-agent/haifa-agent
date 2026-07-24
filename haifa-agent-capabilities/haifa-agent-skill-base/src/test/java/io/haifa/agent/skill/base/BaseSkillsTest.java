package io.haifa.agent.skill.base;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.skill.api.SkillDiscoveryContext;
import io.haifa.agent.skill.api.SkillScope;
import io.haifa.agent.skill.api.SkillVisibilityContext;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BaseSkillsTest {
    @Test
    void exposesOnlyTheTwoInstructionOnlySdkSkills() {
        var visibility = new SkillVisibilityContext(
                new TenantRef("tenant-a"),
                new PrincipalRef("principal-a", "user"),
                Optional.empty(),
                false,
                Set.of(SkillScope.SDK));
        var result = BaseSkills.source().discover(new SkillDiscoveryContext(visibility));

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.registrations())
                .extracting(registration -> registration.metadata().name().value())
                .containsExactly("result-verification", "task-planning");
        assertThat(result.registrations()).allSatisfy(registration -> {
            assertThat(registration.metadata().toolHints()).isEmpty();
            assertThat(registration.packageIndex().resources()).hasSize(1);
        });
    }
}

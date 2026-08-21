package io.haifa.agent.skill.base;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.skill.api.SkillDiscoveryContext;
import io.haifa.agent.skill.api.SkillScope;
import io.haifa.agent.skill.api.SkillVisibilityContext;
import java.nio.charset.StandardCharsets;
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

    @Test
    void resultVerificationSkillChecksTheGenericContractAndValidationScope() throws Exception {
        try (var input =
                BaseSkillsTest.class.getResourceAsStream("/META-INF/haifa-agent/skills/result-verification/SKILL.md")) {
            assertThat(input).isNotNull();
            String skill = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(skill)
                    .contains(
                            "public signatures, visibility, and types",
                            "input and output units and numeric boundaries",
                            "null, invalid-input, error-type, and exact-text requirements",
                            "state changes, side effects, and ordering",
                            "selected, ignored, and discovered test counts",
                            "one selected test is not a complete test-suite claim");
        }
    }

    @Test
    void exposesInstructionOnlyGitCliSkillsWithExecutionHint() {
        var visibility = new SkillVisibilityContext(
                new TenantRef("tenant-a"),
                new PrincipalRef("principal-a", "user"),
                Optional.empty(),
                false,
                Set.of(SkillScope.SDK));
        var result = BaseSkills.gitCliSource().discover(new SkillDiscoveryContext(visibility));

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.registrations())
                .extracting(registration -> registration.metadata().name().value())
                .containsExactly("git", "github");
        assertThat(result.registrations()).allSatisfy(registration -> {
            assertThat(registration.metadata().toolHints())
                    .extracting(alias -> alias.value())
                    .containsExactly("execution_run");
            assertThat(registration.packageIndex().resources()).hasSize(1);
        });
    }
}

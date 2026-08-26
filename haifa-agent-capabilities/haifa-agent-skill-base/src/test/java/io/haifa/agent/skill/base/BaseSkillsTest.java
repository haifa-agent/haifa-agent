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
        var resultVerification = result.registrations().stream()
                .filter(registration -> registration.metadata().name().value().equals("result-verification"))
                .findFirst()
                .orElseThrow();
        assertThat(resultVerification.metadata().declaredVersion())
                .hasValueSatisfying(version -> assertThat(version.value()).isEqualTo("1.3.0"));
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
                            "haifa.version: 1.3.0",
                            "Re-read the original request",
                            "complete contract proportionately",
                            "public API/data",
                            "state/side effects/order",
                            "compatibility, mutation scope",
                            "Map each item to final implementation/evidence",
                            "Core logic or",
                            "happy path cannot cover an omitted contract",
                            "Compare exact contracts literally",
                            "required dynamic values",
                            "expected and actual behavior",
                            "contract-conformance",
                            "Unmet, partial, missing, conflicting, blocked",
                            "is not complete",
                            "public signatures, visibility, and types",
                            "input/output units, grammar, encoding, boundaries, shape, serialization, and framing",
                            "null, invalid-input, error type/code/text",
                            "state, side effects, ordering, compatibility, mutation scope",
                            "Self-invented APIs/tests remain inference",
                            "selected, ignored, and discovered test counts",
                            "one selected test is not a complete test-suite claim",
                            "Recovering actionable evidence from noisy failed checks",
                            "rerun the same",
                            "at most once",
                            "redirect both stdout and stderr",
                            "Save the check's exit code",
                            "at most 20 matches with 3-5 surrounding lines",
                            "Do not `cat` the full log",
                            "Delete the temporary log",
                            "exit with the original check status",
                            "possible flakiness or environment change")
                    .doesNotContain("execution.output.read", "execution_output_read", "capturedOutputRef");
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

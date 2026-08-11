package io.haifa.example.runtime.skill;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.skill.api.SkillScope;
import io.haifa.agent.skill.api.SkillVisibilityContext;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CounterfactualNewsroomSkillPlatformTest {
    @Test
    void freezesAndLoadsTheCounterfactualNewsroomSkill() {
        var persistence = RuntimePersistencePorts.inMemory();
        var platform =
                CounterfactualNewsroomSkillPlatform.create(persistence, () -> Instant.parse("2026-07-30T00:00:00Z"));

        assertThat(platform.skillCatalog().snapshot().bindings())
                .singleElement()
                .satisfies(binding -> {
                    assertThat(binding.alias().value()).isEqualTo(CounterfactualNewsroomSkillPlatform.SKILL_NAME);
                    assertThat(binding.metadata().description()).contains("alternate-history", "causal chain");
                    var visibility = new SkillVisibilityContext(
                            new TenantRef("local"),
                            new PrincipalRef("local-user", "user"),
                            Optional.empty(),
                            false,
                            Set.of(SkillScope.PRODUCT));
                    var content = platform.contentLoader().load(binding, visibility);
                    assertThat(content.instructions())
                            .contains("Build the causal spine", "Run the continuity desk", "compact-edition mode");
                });
        assertThat(platform.toolCatalog().snapshot().bindings()).singleElement().satisfies(binding -> {
            assertThat(binding.alias().value()).isEqualTo(CounterfactualNewsroomSkillPlatform.SKILL_LOAD_ALIAS);
            assertThat(binding.definition().name().value()).isEqualTo("skill.load");
        });
    }
}

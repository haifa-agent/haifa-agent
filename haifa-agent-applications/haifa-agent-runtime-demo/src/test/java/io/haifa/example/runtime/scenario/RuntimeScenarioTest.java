package io.haifa.example.runtime.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.tool.api.ToolAlias;
import org.junit.jupiter.api.Test;

class RuntimeScenarioTest {
    @Test
    void modelOnlyScenarioHasNoCapabilityBindings() {
        RuntimeScenario scenario = new ModelOnlyRuntimeScenario();

        assertThat(scenario.id()).isEqualTo("model-only");
        assertThat(scenario.defaultObjective()).isEqualTo("Reply with exactly DEEPSEEK_V4_PRO_RUNTIME_OK.");
        assertThat(scenario.allowedToolAliases()).isEmpty();
        assertThat(scenario.allowedSkillAliases()).isEmpty();
        assertThat(scenario.toolCatalog()).isEmpty();
    }

    @Test
    void rawToolScenarioCreatesOnePureLowRiskBinding() {
        var scenario = new RawToolRuntimeScenario();

        assertThat(scenario.catalog().snapshot().bindings()).singleElement().satisfies(binding -> {
            assertThat(binding.alias()).isEqualTo(new ToolAlias(RawToolRuntimeScenario.TOOL_ALIAS));
            assertThat(binding.definition().sideEffects()).isEmpty();
            assertThat(binding.definition().risk().name()).isEqualTo("LOW");
            assertThat(binding.definition().idempotency().name()).isEqualTo("PURE");
        });
        assertThat(scenario.defaultObjective()).contains("demo_echo", "DEEPSEEK_V4_PRO_TOOL_OK");
    }

    @Test
    void skillScenarioProvidesAThreeEraCounterfactualByDefault() {
        var persistence = io.haifa.agent.runtime.core.storage.RuntimePersistencePorts.inMemory();
        var scenario = SkillRuntimeScenario.create(persistence, () -> java.time.Instant.parse("2026-07-30T00:00:00Z"));

        assertThat(scenario.defaultObjective()).contains("1996", "2008", "2025", "因果连续性");
        assertThat(scenario.allowedToolAliases()).containsExactly("skill_load");
        assertThat(scenario.allowedSkillAliases()).containsExactly("run-counterfactual-newsrooms");
    }
}

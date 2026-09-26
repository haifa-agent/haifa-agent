package io.haifa.agent.sdk.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProductProfileTest {

    @Test
    void exposesProductSelectionAndDefaults() {
        ProductProfile profile = profile(Set.of("memory.search"), Set.of("plan"));

        assertThat(profile.productId().value()).isEqualTo("profile-test");
        assertThat(profile.productVersion().value()).isEqualTo("1.0.0");
        assertThat(profile.definitionId().value()).isEqualTo("profile-test-agent");
        assertThat(profile.definitionVersion()).isEqualTo(new AgentDefinitionVersion(1, 0, 0));
        assertThat(profile.defaultRunProfile()).isEqualTo(new ProductRunProfileRef("profile-test-chat", "1.0.0"));
        assertThat(profile.allowedTools()).containsExactly("memory.search");
        assertThat(profile.allowedSkills()).containsExactly("plan");
    }

    @Test
    void rejectsBlankInstructionsAndMissingFields() {
        assertThatThrownBy(() -> ProductProfile.create(
                        new ProductId("profile-test"),
                        new ProductVersion("1.0.0"),
                        new AgentDefinitionId("profile-test-agent"),
                        new AgentDefinitionVersion(1, 0, 0),
                        " ",
                        new ProductRunProfileRef("profile-test-chat", "1.0.0"),
                        new AgentRunBudget(1_000, 1_000, 1_000, 2, 2, 0, "USD", 100),
                        new AgentRunLimits(2, 0, 1, 10_000, 10_000),
                        Set.of(),
                        Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("instructions");
    }

    @Test
    void rejectsBlankDefaultRunProfileValues() {
        assertThatThrownBy(() -> new ProductRunProfileRef(" ", "1.0.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
        assertThatThrownBy(() -> new ProductRunProfileRef("profile-test-chat", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
    }

    @Test
    void policiesFailClosedOnUnsafeMemoryAndDisabledExecutionShapes() {
        assertThatThrownBy(() -> new ProductMemoryPolicy(32_000, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxContentChars");
        assertThatThrownBy(() -> new ProductArtifactPolicy(1_000, 0, 0, Set.of("text/plain"), false, 0, 0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disabled artifact policy");
    }

    private static ProductProfile profile(Set<String> tools, Set<String> skills) {
        return ProductProfile.create(
                new ProductId("profile-test"),
                new ProductVersion("1.0.0"),
                new AgentDefinitionId("profile-test-agent"),
                new AgentDefinitionVersion(1, 0, 0),
                "Safe instructions.",
                new ProductRunProfileRef("profile-test-chat", "1.0.0"),
                new AgentRunBudget(1_000, 1_000, 1_000, 2, 2, 0, "USD", 100),
                new AgentRunLimits(2, 0, 1, 10_000, 10_000),
                tools,
                skills);
    }
}

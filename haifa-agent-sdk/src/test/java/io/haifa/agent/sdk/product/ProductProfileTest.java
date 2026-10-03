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
    void childDefinitionsAcceptManagedNamesAndRetainTrustedText() {
        for (String id : java.util.List.of("1-worker", "-worker", "-", "sdk_child", "worker".repeat(60))) {
            ChildAgentSpec child = ChildAgentSpec.of(id, "d".repeat(2_000), "i".repeat(33_000), Set.of());
            assertThat(child.id()).isEqualTo(id);
            assertThat(child.description()).hasSize(2_000);
            assertThat(child.instructions()).hasSize(33_000);
            String profileId = "child/" + id;
            assertThat(new ProductRunProfileRef(profileId, "1.0.0").id()).isEqualTo(profileId);
            ProductProfile parent = profile(Set.of(), Set.of());
            assertThat(new ProductRunProfile(
                                    profileId,
                                    "1.0.0",
                                    "model",
                                    io.haifa.agent.core.run.AgentRunType.CHAT,
                                    parent.budget(),
                                    parent.limits(),
                                    java.util.Map.of())
                            .id())
                    .isEqualTo(profileId);
            assertThat(profile(Set.of(), Set.of())
                            .withAllowedChildAgents(Set.of(id))
                            .allowedChildAgents())
                    .containsExactly(id);
        }
    }

    @Test
    void childDefinitionCompatibilityRetainsInvalidIdentityAndBlankTextRejection() {
        for (String id : java.util.List.of("", " ", "Bad Id", "Uppercase", "../child", "child/path", "child.name")) {
            assertThatThrownBy(() -> ChildAgentSpec.of(id, "description", "instructions", Set.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> ChildAgentSpec.of("worker", " ", "instructions", Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChildAgentSpec.of("worker", "description", " ", Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
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

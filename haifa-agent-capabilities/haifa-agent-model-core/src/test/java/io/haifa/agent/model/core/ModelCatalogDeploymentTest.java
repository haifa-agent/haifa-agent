package io.haifa.agent.model.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelAuthenticationMethod;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProviderId;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModelCatalogDeploymentTest {
    @Test
    void projectsOnlyTheDeploymentAllowlistAndKeepsCatalogBindingIdentity() {
        ModelCatalogManifest manifest = packagedCatalog();
        ModelCatalogDeployment deployment =
                new ModelCatalogDeployment(java.util.List.of(new ModelCatalogDeployment.Provider(
                        new ModelProviderId("deepseek"),
                        URI.create("https://api.deepseek.com"),
                        new CredentialRef("env://DEEPSEEK_API_KEY"),
                        true,
                        true,
                        Set.of(new ModelDefinitionId("deepseek-chat-pro")),
                        Map.of())));

        ModelCatalogProjection projection = manifest.project(deployment);

        assertThat(projection.providers()).hasSize(1);
        assertThat(projection.providers().getFirst().models())
                .extracting(model -> model.id().value())
                .containsExactly("deepseek-chat-pro");
        assertThat(projection
                        .binding("deepseek-chat-pro")
                        .orElseThrow()
                        .definition()
                        .version())
                .isEqualTo("2026-09-m0");
        assertThat(projection
                        .binding("deepseek-chat-pro")
                        .orElseThrow()
                        .profile()
                        .contextWindowTokens())
                .isEqualTo(1_048_576);
        assertThat(projection
                        .binding("deepseek-chat-pro")
                        .orElseThrow()
                        .profile()
                        .executionLimits()
                        .maximumOutputTokens())
                .isEqualTo(393_216);
        assertThat(projection
                        .binding("deepseek-chat-pro")
                        .orElseThrow()
                        .profile()
                        .digest())
                .isEqualTo(manifest.binding("deepseek-chat-pro")
                        .orElseThrow()
                        .profile()
                        .digest());
    }

    @Test
    void usesOnlyAnExplicitEndpointOverrideForTheExactBinding() {
        ModelCatalogManifest manifest = packagedCatalog();
        ModelCatalogDeployment deployment =
                new ModelCatalogDeployment(java.util.List.of(new ModelCatalogDeployment.Provider(
                        new ModelProviderId("deepseek"),
                        URI.create("https://api.deepseek.com"),
                        new CredentialRef("env://DEEPSEEK_API_KEY"),
                        true,
                        true,
                        Set.of(new ModelDefinitionId("deepseek-anthropic-pro")),
                        Map.of(
                                new ModelDefinitionId("deepseek-anthropic-pro"),
                                URI.create("https://api.deepseek.com/anthropic")))));

        ModelCatalogProjection projection = manifest.project(deployment);

        assertThat(projection.providers().getFirst().apiBindings().getFirst().endpoint())
                .contains(URI.create("https://api.deepseek.com/anthropic"));
    }

    @Test
    void rejectsADeploymentBindingThatDoesNotBelongToTheProvider() {
        ModelCatalogManifest manifest = packagedCatalog();
        ModelCatalogDeployment deployment =
                new ModelCatalogDeployment(java.util.List.of(new ModelCatalogDeployment.Provider(
                        new ModelProviderId("deepseek"),
                        URI.create("https://api.deepseek.com"),
                        new CredentialRef("env://DEEPSEEK_API_KEY"),
                        true,
                        true,
                        Set.of(new ModelDefinitionId("antigravity-gemini")),
                        Map.of())));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> manifest.project(deployment))
                .withMessageContaining("does not belong to provider");
    }

    private static ModelCatalogManifest packagedCatalog() {
        return ModelCatalogYamlLoader.fromClasspath(
                        ModelCatalogDeploymentTest.class.getClassLoader(),
                        Map.of(
                                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                                        Set.of(
                                                "deepseek-openai-chat",
                                                "aliyun-bailian-openai-chat",
                                                "siliconflow-openai-chat",
                                                "kimi-openai-chat",
                                                "zhipu-openai-chat",
                                                "tokenrhythm-openai-chat"),
                                ModelApiStyles.OPENAI_RESPONSES,
                                        Set.of("deepseek-openai-responses", "openai-codex-responses"),
                                ModelApiStyles.ANTHROPIC_MESSAGES, Set.of("deepseek-anthropic-messages"),
                                ModelApiStyles.GOOGLE_GEMINI_GENERATE_CONTENT, Set.of("antigravity-direct")),
                        Map.of(
                                new ModelProviderId("deepseek"), Set.of(ModelAuthenticationMethod.API_KEY),
                                new ModelProviderId("openai-codex"), Set.of(ModelAuthenticationMethod.EXTERNAL_LOGIN),
                                new ModelProviderId("aliyun-bailian"), Set.of(ModelAuthenticationMethod.API_KEY),
                                new ModelProviderId("siliconflow"), Set.of(ModelAuthenticationMethod.API_KEY),
                                new ModelProviderId("kimi"), Set.of(ModelAuthenticationMethod.API_KEY),
                                new ModelProviderId("zhipu"), Set.of(ModelAuthenticationMethod.API_KEY),
                                new ModelProviderId("tokenrhythm"), Set.of(ModelAuthenticationMethod.API_KEY),
                                new ModelProviderId("google-antigravity"),
                                        Set.of(ModelAuthenticationMethod.EXTERNAL_LOGIN)))
                .load();
    }
}

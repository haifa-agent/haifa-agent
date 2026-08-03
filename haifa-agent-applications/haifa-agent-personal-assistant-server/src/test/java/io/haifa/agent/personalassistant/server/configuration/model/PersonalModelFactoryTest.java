package io.haifa.agent.personalassistant.server.configuration.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.personalassistant.server.configuration.product.PersonalAssistantProperties;
import io.haifa.agent.sdk.api.SdkConfigurationDigest;
import io.haifa.agent.sdk.contribution.SdkContributionMetadata;
import io.haifa.agent.sdk.contribution.ShellPlatformContribution;
import io.haifa.agent.sdk.product.ProductCapabilities;
import io.haifa.agent.sdk.product.ProductContributionCoordinate;
import io.haifa.agent.sdk.product.ProductProviderSuitability;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class PersonalModelFactoryTest {
    @Test
    void bindsProviderWithItsAvailableModelList() {
        var source = new MapConfigurationPropertySource(Map.ofEntries(
                Map.entry("provider.id", "deepseek"),
                Map.entry("provider.display-name", "DeepSeek"),
                Map.entry("provider.mode", "remote"),
                Map.entry("provider.dialect-id", "deepseek-openai-chat"),
                Map.entry("provider.dialect-version", "1.0"),
                Map.entry("provider.native-streaming", true),
                Map.entry("provider.endpoint", "https://api.deepseek.com"),
                Map.entry("provider.credential-reference", "env://DEEPSEEK_API_KEY"),
                Map.entry("provider.models[0].id", "deepseek-v4-pro"),
                Map.entry("provider.models[0].display-name", "DeepSeek V4 Pro"),
                Map.entry("provider.models[0].provider-model-id", "deepseek-v4-pro"),
                Map.entry("provider.models[1].id", "deepseek-v4-flash"),
                Map.entry("provider.models[1].display-name", "DeepSeek V4 Flash"),
                Map.entry("provider.models[1].provider-model-id", "deepseek-v4-flash")));

        var provider = new Binder(source)
                .bind("provider", Bindable.of(PersonalAssistantProperties.ModelProvider.class))
                .orElseThrow(() -> new AssertionError("model provider did not bind"));

        assertThat(provider.id()).isEqualTo("deepseek");
        assertThat(provider.dialectId()).isEqualTo("deepseek-openai-chat");
        assertThat(provider.nativeStreaming()).isTrue();
        assertThat(provider.models())
                .extracting(PersonalAssistantProperties.ProviderModel::id)
                .containsExactly("deepseek-v4-pro", "deepseek-v4-flash");
    }

    @Test
    void exposesTwoModelsUnderOneProviderAndFreezesBothSnapshots() {
        var provider = new PersonalAssistantProperties.ModelProvider(
                "deepseek",
                "DeepSeek",
                "remote",
                false,
                "deepseek-openai-chat",
                "1.0",
                true,
                URI.create("https://api.deepseek.com"),
                "env://DEEPSEEK_API_KEY",
                List.of(
                        new PersonalAssistantProperties.ProviderModel(
                                "deepseek-v4-pro", "DeepSeek V4 Pro", "deepseek-v4-pro"),
                        new PersonalAssistantProperties.ProviderModel(
                                "deepseek-v4-flash", "DeepSeek V4 Flash", "deepseek-v4-flash")));

        var platform =
                PersonalModelFactory.createPlatform(List.of(provider), "deepseek-v4-pro", new ObjectMapper(), shell());

        assertThat(platform.catalog().available())
                .extracting(model -> model.providerId() + "/" + model.id())
                .containsExactly("deepseek/deepseek-v4-pro", "deepseek/deepseek-v4-flash");
        assertThat(platform.contribution().snapshots()).containsOnlyKeys("deepseek-v4-pro", "deepseek-v4-flash");
        assertThat(platform.contribution().snapshot().modelId().value()).isEqualTo("deepseek-v4-pro");
    }

    @Test
    void freezesOpenAiAsASecondProviderWithoutDeepSeekOptions() {
        var deepSeek = new PersonalAssistantProperties.ModelProvider(
                "deepseek",
                "DeepSeek",
                "remote",
                false,
                "deepseek-openai-chat",
                "1.0",
                true,
                URI.create("https://api.deepseek.com"),
                "env://DEEPSEEK_API_KEY",
                List.of(new PersonalAssistantProperties.ProviderModel(
                        "deepseek-v4-flash", "DeepSeek V4 Flash", "deepseek-v4-flash")));
        var openAi = new PersonalAssistantProperties.ModelProvider(
                "openai",
                "OpenAI",
                "remote",
                false,
                "openai-chat-completions",
                "1.0",
                false,
                URI.create("http://localhost:30000/v1"),
                "env://OPENAI_API_KEY",
                List.of(new PersonalAssistantProperties.ProviderModel(
                        "openai-gpt-5.6-luna", "GPT-5.6 Luna", "gpt-5.6-luna")));

        var platform = PersonalModelFactory.createPlatform(
                List.of(deepSeek, openAi), "deepseek-v4-flash", true, new ObjectMapper(), shell());

        assertThat(platform.catalog().available())
                .extracting(model -> model.providerId() + "/" + model.id())
                .containsExactly("deepseek/deepseek-v4-flash", "openai/openai-gpt-5.6-luna");
        assertThat(platform.contribution()
                        .snapshots()
                        .get("openai-gpt-5.6-luna")
                        .providerOptions())
                .containsEntry("dialect_id", "openai-chat-completions")
                .containsEntry("dialect_version", "1.0")
                .doesNotContainKeys("thinking", "reasoning_effort");
    }

    @Test
    void freezesStandardChatCompletionsDialectForAnArbitraryProviderId() {
        var provider = new PersonalAssistantProperties.ModelProvider(
                "third-party-openai",
                "Third-party OpenAI-compatible",
                "remote",
                false,
                "openai-chat-completions",
                "1.0",
                true,
                URI.create("https://gateway.example.com/v1"),
                "env://THIRD_PARTY_API_KEY",
                List.of(new PersonalAssistantProperties.ProviderModel(
                        "third-party-chat", "Third-party Chat", "vendor-chat-model")));

        var platform =
                PersonalModelFactory.createPlatform(List.of(provider), "third-party-chat", new ObjectMapper(), shell());

        assertThat(platform.contribution().snapshot().providerId().value()).isEqualTo("third-party-openai");
        assertThat(platform.contribution().snapshot().providerOptions())
                .containsEntry("dialect_id", "openai-chat-completions")
                .containsEntry("dialect_version", "1.0")
                .containsEntry("native_streaming", true)
                .containsEntry("endpoint_host", "gateway.example.com")
                .doesNotContainKeys("thinking", "reasoning_effort");
    }

    @Test
    void permitsInsecureHttpOnlyForExplicitLoopbackModelEndpoints() {
        var loopback = new PersonalAssistantProperties.ModelProvider(
                "openai",
                "OpenAI",
                "remote",
                false,
                "openai-chat-completions",
                "1.0",
                false,
                URI.create("http://localhost:30000/v1"),
                "env://OPENAI_API_KEY",
                List.of(new PersonalAssistantProperties.ProviderModel(
                        "openai-gpt-5.6-luna", "GPT-5.6 Luna", "gpt-5.6-luna")));
        var external = new PersonalAssistantProperties.ModelProvider(
                "openai",
                "OpenAI",
                "remote",
                false,
                "openai-chat-completions",
                "1.0",
                false,
                URI.create("http://example.com/v1"),
                "env://OPENAI_API_KEY",
                loopback.models());

        assertThatThrownBy(() -> PersonalModelFactory.createPlatform(
                        List.of(loopback), "openai-gpt-5.6-luna", false, new ObjectMapper(), shell()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback-only opt-in");
        assertThatThrownBy(() -> PersonalModelFactory.createPlatform(
                        List.of(external), "openai-gpt-5.6-luna", true, new ObjectMapper(), shell()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback-only opt-in");
    }

    private static ShellPlatformContribution shell() {
        return new ShellPlatformContribution(
                new SdkContributionMetadata(
                        new ProductContributionCoordinate("personal-model-test-shell", "1.0.0"),
                        ProductCapabilities.SHELL,
                        SdkConfigurationDigest.sha256("personal-model-test-shell"),
                        ProductProviderSuitability.TEST_ONLY,
                        "Personal model test shell"),
                "UNIX",
                Set.of("bash"));
    }
}

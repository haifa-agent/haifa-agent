package io.haifa.agent.personalassistant.server.configuration.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCallId;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelReasoningEffort;
import io.haifa.agent.model.api.ModelReasoningMode;
import io.haifa.agent.model.openai.OpenAiCompatibleDialects;
import io.haifa.agent.personalassistant.application.PersonalModelPreferences;
import io.haifa.agent.personalassistant.application.PersonalModelSelectionRequest;
import io.haifa.agent.personalassistant.application.PersonalResponseLength;
import io.haifa.agent.personalassistant.application.PersonalResponseMode;
import io.haifa.agent.personalassistant.server.configuration.product.PersonalAssistantProperties;
import io.haifa.agent.sdk.api.SdkConfigurationDigest;
import io.haifa.agent.sdk.contribution.SdkContributionMetadata;
import io.haifa.agent.sdk.contribution.ShellPlatformContribution;
import io.haifa.agent.sdk.product.ProductCapabilities;
import io.haifa.agent.sdk.product.ProductContributionCoordinate;
import io.haifa.agent.sdk.product.ProductProviderSuitability;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

class PersonalModelFactoryTest {
    @Test
    void modelCredentialsAcceptOnlyEnvironmentOrSharedLocalAuthReferences() {
        assertThatThrownBy(() -> provider(
                        "deepseek",
                        "DeepSeek",
                        false,
                        URI.create("https://api.deepseek.com"),
                        "vault://deepseek",
                        List.of(new PersonalAssistantProperties.ApiBinding(
                                "openai-chat-completions", "deepseek-openai-chat", null)),
                        List.of(model(
                                "deepseek-v4-flash",
                                "DeepSeek V4 Flash",
                                "deepseek-v4-flash",
                                "openai-chat-completions"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("env:// or model-auth://");

        assertThatThrownBy(() -> provider(
                        "openai-codex",
                        "OpenAI Codex",
                        false,
                        URI.create("https://chatgpt.com/backend-api/codex"),
                        "model-auth://openai/default",
                        List.of(new PersonalAssistantProperties.ApiBinding(
                                "openai-responses", "openai-codex-responses", null)),
                        List.of(textModel("codex", "Codex", "gpt-5.6-codex", "openai-responses"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model-auth://openai-codex/");

        assertThatThrownBy(() -> provider(
                        "google-antigravity",
                        "Google Antigravity Direct",
                        true,
                        URI.create("https://daily-cloudcode-pa.googleapis.com/v1internal"),
                        "env://GOOGLE_TOKEN",
                        List.of(new PersonalAssistantProperties.ApiBinding(
                                "google-gemini-generate-content", "antigravity-direct", null)),
                        List.of(model(
                                "antigravity-gemini",
                                "Gemini via Antigravity Direct",
                                "gemini-3-flash",
                                "google-gemini-generate-content"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model-auth://google-antigravity/default");
    }

    @Test
    void productionAdapterUsesTheInjectedCredentialResolver() {
        var resolved = new java.util.concurrent.atomic.AtomicBoolean();
        var provider = provider(
                "deepseek",
                "DeepSeek",
                false,
                URI.create("https://api.deepseek.com"),
                "model-auth://deepseek/default",
                List.of(new PersonalAssistantProperties.ApiBinding(
                        "openai-chat-completions", "deepseek-openai-chat", null)),
                List.of(model(
                        "deepseek-v4-flash", "DeepSeek V4 Flash", "deepseek-v4-flash", "openai-chat-completions")));
        var platform = PersonalModelFactory.createPlatform(
                List.of(provider), "deepseek-v4-flash", false, new ObjectMapper(), shell(), reference -> {
                    resolved.set(true);
                    assertThat(reference.value()).isEqualTo("model-auth://deepseek/default");
                    throw new IllegalStateException("INJECTED_RESOLVER_USED");
                });
        var snapshot = platform.contribution().snapshot();
        var adapter = platform.contribution().adapters().values().iterator().next();
        var request = new AgentChatRequest(
                new ModelCallId("call-injected-resolver"),
                new AgentRunId("run-injected-resolver"),
                1,
                1,
                snapshot,
                List.of(ModelMessage.text(ModelMessageRole.USER, "hello")),
                List.of(),
                32,
                Duration.ofSeconds(1),
                Map.of());

        assertThatThrownBy(() -> adapter.invoke(request))
                .isInstanceOf(io.haifa.agent.model.api.ModelInvocationException.class)
                .hasMessage("model credential is unavailable");
        assertThat(resolved).isTrue();
        assertThat(platform.catalog().available()).hasSize(1);
    }

    @Test
    void defaultApplicationConfigurationIsDeepSeekOnly() throws Exception {
        var sources = new MutablePropertySources();
        var resource = new ClassPathResource("application.yml");
        for (var source : new YamlPropertySourceLoader().load("application", resource)) {
            sources.addLast(source);
        }

        var providers = new Binder(
                        ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources))
                .bind(
                        "haifa.personal.model-providers",
                        Bindable.listOf(PersonalAssistantProperties.ModelProvider.class))
                .orElseThrow(() -> new AssertionError("default model providers did not bind"));

        assertThat(providers)
                .extracting(PersonalAssistantProperties.ModelProvider::id)
                .containsExactly("deepseek");
    }

    @Test
    void defaultApplicationConfigurationRequiresExplicitTaskAutoRetryOptIn() throws Exception {
        var sources = new MutablePropertySources();
        var resource = new ClassPathResource("application.yml");
        for (var source : new YamlPropertySourceLoader().load("application", resource)) {
            sources.addLast(source);
        }

        int attempts = new Binder(
                        ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources))
                .bind("haifa.personal.mission.max-auto-attempts-per-task", Integer.class)
                .orElseThrow(() -> new AssertionError("default Mission Attempt limit did not bind"));

        assertThat(attempts).isEqualTo(1);
    }

    @Test
    void bindsProviderWithItsAvailableModelList() {
        var source = new MapConfigurationPropertySource(Map.ofEntries(
                Map.entry("provider.id", "deepseek"),
                Map.entry("provider.display-name", "DeepSeek"),
                Map.entry("provider.mode", "remote"),
                Map.entry("provider.native-streaming", true),
                Map.entry("provider.endpoint", "https://api.deepseek.com"),
                Map.entry("provider.credential-reference", "env://DEEPSEEK_API_KEY"),
                Map.entry("provider.api-bindings[0].style", "openai-chat-completions"),
                Map.entry("provider.api-bindings[0].dialect", "deepseek-openai-chat"),
                Map.entry("provider.models[0].id", "deepseek-v4-pro"),
                Map.entry("provider.models[0].display-name", "DeepSeek V4 Pro"),
                Map.entry("provider.models[0].provider-model-id", "deepseek-v4-pro"),
                Map.entry("provider.models[0].style", "openai-chat-completions"),
                Map.entry("provider.models[0].capabilities[0]", "TEXT_CHAT"),
                Map.entry("provider.models[0].capabilities[1]", "TOOL_CALLING"),
                Map.entry("provider.models[0].context-window", 131072),
                Map.entry("provider.models[0].max-output-tokens", 8192),
                Map.entry("provider.models[1].id", "deepseek-v4-flash"),
                Map.entry("provider.models[1].display-name", "DeepSeek V4 Flash"),
                Map.entry("provider.models[1].provider-model-id", "deepseek-v4-flash"),
                Map.entry("provider.models[1].style", "openai-chat-completions"),
                Map.entry("provider.models[1].capabilities[0]", "TEXT_CHAT"),
                Map.entry("provider.models[1].capabilities[1]", "TOOL_CALLING"),
                Map.entry("provider.models[1].context-window", 131072),
                Map.entry("provider.models[1].max-output-tokens", 8192)));

        var provider = new Binder(source)
                .bind("provider", Bindable.of(PersonalAssistantProperties.ModelProvider.class))
                .orElseThrow(() -> new AssertionError("model provider did not bind"));

        assertThat(provider.id()).isEqualTo("deepseek");
        assertThat(provider.apiBindings()).singleElement().satisfies(binding -> {
            assertThat(binding.style()).isEqualTo("openai-chat-completions");
            assertThat(binding.dialect()).isEqualTo("deepseek-openai-chat");
        });
        assertThat(provider.nativeStreaming()).isTrue();
        assertThat(provider.models())
                .extracting(PersonalAssistantProperties.ProviderModel::id)
                .containsExactly("deepseek-v4-pro", "deepseek-v4-flash");
    }

    @Test
    void exposesTwoModelsUnderOneProviderAndFreezesBothSnapshots() {
        var provider = provider(
                "deepseek",
                "DeepSeek",
                true,
                URI.create("https://api.deepseek.com"),
                "env://DEEPSEEK_API_KEY",
                List.of(new PersonalAssistantProperties.ApiBinding(
                        "openai-chat-completions", "deepseek-openai-chat", null)),
                List.of(
                        model("deepseek-v4-pro", "DeepSeek V4 Pro", "deepseek-v4-pro", "openai-chat-completions"),
                        model(
                                "deepseek-v4-flash",
                                "DeepSeek V4 Flash",
                                "deepseek-v4-flash",
                                "openai-chat-completions")));

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
        var deepSeek = provider(
                "deepseek",
                "DeepSeek",
                true,
                URI.create("https://api.deepseek.com"),
                "env://DEEPSEEK_API_KEY",
                List.of(new PersonalAssistantProperties.ApiBinding(
                        "openai-responses", "deepseek-openai-responses", null)),
                List.of(model(
                        "deepseek-responses-flash",
                        "DeepSeek Responses Flash",
                        "deepseek-v4-flash",
                        "openai-responses")));
        var openAi = provider(
                "openai",
                "OpenAI",
                false,
                URI.create("http://localhost:30000/v1"),
                "env://OPENAI_API_KEY",
                List.of(new PersonalAssistantProperties.ApiBinding("openai-responses", null, null)),
                List.of(textModel("openai-gpt-5.6-luna", "GPT-5.6 Luna", "gpt-5.6-luna", "openai-responses")));

        var platform = PersonalModelFactory.createPlatform(
                List.of(deepSeek, openAi), "deepseek-responses-flash", true, new ObjectMapper(), shell());

        assertThat(platform.catalog().available())
                .extracting(model -> model.providerId() + "/" + model.id())
                .containsExactly("deepseek/deepseek-responses-flash");
        assertThat(platform.catalog().find("openai-gpt-5.6-luna")).isEmpty();
        var snapshot = platform.contribution().snapshots().get("openai-gpt-5.6-luna");
        assertThat(snapshot.apiStyle()).isEqualTo(ModelApiStyles.OPENAI_RESPONSES);
        assertThat(snapshot.dialect()).isEqualTo("standard");
        assertThat(snapshot.nativeStreaming()).isFalse();
        assertThat(snapshot.providerOptions()).doesNotContainKeys("thinking", "reasoning_effort");
    }

    @Test
    void freezesBailianWorkspaceAndRegionAndExposesTheVerifiedBinding() {
        var deepSeek = provider(
                "deepseek",
                "DeepSeek",
                true,
                URI.create("https://api.deepseek.com"),
                "env://DEEPSEEK_API_KEY",
                List.of(new PersonalAssistantProperties.ApiBinding(
                        "openai-chat-completions", OpenAiCompatibleDialects.DEEPSEEK, null)),
                List.of(model(
                        "deepseek-v4-flash", "DeepSeek V4 Flash", "deepseek-v4-flash", "openai-chat-completions")));
        var bailian = provider(
                "aliyun-bailian",
                "Alibaba Cloud Model Studio",
                true,
                URI.create("https://workspace-123.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"),
                "env://DASHSCOPE_API_KEY",
                List.of(new PersonalAssistantProperties.ApiBinding(
                        "openai-chat-completions", OpenAiCompatibleDialects.ALIYUN_BAILIAN, null)),
                List.of(model(
                        "qwen3.7-max-2026-05-17",
                        "Qwen3.7 Max",
                        "qwen3.7-max-2026-05-17",
                        "openai-chat-completions",
                        ModelReasoningMode.ENABLED)));

        var platform = PersonalModelFactory.createPlatform(
                List.of(deepSeek, bailian), "deepseek-v4-flash", new ObjectMapper(), shell());

        assertThat(platform.catalog().available())
                .extracting(model -> model.providerId() + "/" + model.id())
                .contains("aliyun-bailian/qwen3.7-max-2026-05-17");
        assertThat(platform.contribution()
                        .snapshots()
                        .get("qwen3.7-max-2026-05-17")
                        .providerOptions())
                .containsEntry("workspace_id", "workspace-123")
                .containsEntry("region", "cn-beijing");
        assertThat(platform.contribution()
                        .snapshots()
                        .get("qwen3.7-max-2026-05-17")
                        .invocationOptions())
                .containsEntry("thinking_profile", "always")
                .containsEntry("thinking_enabled", true)
                .containsEntry("preserve_thinking", true)
                .containsEntry("requires_reasoning_continuation", true);
        assertThat(platform.catalog().available().stream()
                        .filter(model -> model.id().equals("qwen3.7-max-2026-05-17"))
                        .findFirst())
                .get()
                .satisfies(model -> {
                    assertThat(model.availability()).isEqualTo("AVAILABLE");
                    assertThat(model.unavailableReason()).isEmpty();
                });
        assertThat(platform.catalog().binding("qwen3.7-max-2026-05-17")).isPresent();
    }

    @Test
    void rejectsBailianEndpointsOutsideTheWorkspaceScopedEndpointContract() {
        List<URI> invalidEndpoints = List.of(
                URI.create("https://workspace-123.cn-shanghai.maas.aliyuncs.com/v1"),
                URI.create("https://workspace-123.maas.aliyuncs.com/compatible-mode/v1"),
                URI.create("https://workspace-123.cn-beijing.maas.aliyuncs.com.evil.example/compatible-mode/v1"));

        for (URI endpoint : invalidEndpoints) {
            var bailian = provider(
                    "aliyun-bailian",
                    "Alibaba Cloud Model Studio",
                    true,
                    endpoint,
                    "env://DASHSCOPE_API_KEY",
                    List.of(new PersonalAssistantProperties.ApiBinding(
                            "openai-chat-completions", OpenAiCompatibleDialects.ALIYUN_BAILIAN, null)),
                    List.of(model(
                            "qwen3.7-max-2026-05-17",
                            "Qwen3.7 Max",
                            "qwen3.7-max-2026-05-17",
                            "openai-chat-completions")));

            assertThatThrownBy(() -> PersonalModelFactory.createPlatform(
                            List.of(bailian), "qwen3.7-max-2026-05-17", new ObjectMapper(), shell()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Bailian endpoint");
        }
    }

    @Test
    void freezesReviewedKimiAndZhipuControlsWithProtectedToolContinuation() {
        var kimi = provider(
                "kimi",
                "Kimi",
                true,
                URI.create("https://api.moonshot.cn/v1"),
                "env://KIMI_API_KEY",
                List.of(new PersonalAssistantProperties.ApiBinding(
                        "openai-chat-completions", OpenAiCompatibleDialects.KIMI, null)),
                List.of(model("kimi-k3", "Kimi K3", "kimi-k3", "openai-chat-completions", ModelReasoningMode.ENABLED)));
        var zhipu = provider(
                "zhipu",
                "Zhipu GLM",
                true,
                URI.create("https://open.bigmodel.cn/api/paas/v4"),
                "env://BIGMODEL_API_KEY",
                List.of(new PersonalAssistantProperties.ApiBinding(
                        "openai-chat-completions", OpenAiCompatibleDialects.ZHIPU, null)),
                List.of(model(
                        "glm-5.2-chat", "GLM-5.2", "glm-5.2", "openai-chat-completions", ModelReasoningMode.ADAPTIVE)));

        var platform =
                PersonalModelFactory.createPlatform(List.of(kimi, zhipu), "kimi-k3", new ObjectMapper(), shell());

        assertThat(platform.contribution().snapshots().get("kimi-k3").invocationOptions())
                .containsEntry("thinking", "enabled")
                .containsEntry("requires_reasoning_continuation", true);
        assertThat(platform.contribution().snapshots().get("glm-5.2-chat").invocationOptions())
                .containsEntry("thinking", "adaptive")
                .containsEntry("do_sample", false)
                .containsEntry("clear_thinking", false)
                .containsEntry("requires_reasoning_continuation", true);
        var kimiOption = platform.catalog().find("kimi-k3").orElseThrow();
        assertThat(kimiOption.controls().responseMode().allowedValues())
                .containsExactly(PersonalResponseMode.RECOMMENDED, PersonalResponseMode.DEEP);
        assertThat(kimiOption.controls().reasoningEffort().allowedValues())
                .containsExactly(ModelReasoningEffort.LOW, ModelReasoningEffort.HIGH, ModelReasoningEffort.MAX);
        var zhipuOption = platform.catalog().find("glm-5.2-chat").orElseThrow();
        assertThat(zhipuOption.controls().responseMode().allowedValues())
                .containsExactly(
                        PersonalResponseMode.RECOMMENDED, PersonalResponseMode.FAST, PersonalResponseMode.DEEP);
        assertThat(zhipuOption.controls().reasoningEffort().allowedValues())
                .containsExactly(ModelReasoningEffort.HIGH, ModelReasoningEffort.MAX);
    }

    @Test
    void exposesReviewedSiliconFlowBindingToThePersonalModelCatalog() {
        var siliconFlow = provider(
                "siliconflow",
                "硅基流动 SiliconFlow",
                true,
                URI.create("https://api.siliconflow.cn/v1"),
                "env://SILICONFLOW_API_KEY",
                List.of(new PersonalAssistantProperties.ApiBinding(
                        "openai-chat-completions", OpenAiCompatibleDialects.SILICONFLOW, null)),
                List.of(model(
                        "siliconflow-deepseek-v4-flash",
                        "DeepSeek V4 Flash",
                        "deepseek-ai/DeepSeek-V4-Flash",
                        "openai-chat-completions")));

        var platform = PersonalModelFactory.createPlatform(
                List.of(siliconFlow), "siliconflow-deepseek-v4-flash", new ObjectMapper(), shell());
        var snapshot = platform.contribution().snapshot();
        var option = platform.catalog().find("siliconflow-deepseek-v4-flash").orElseThrow();

        assertThat(snapshot.providerId().value()).isEqualTo("siliconflow");
        assertThat(snapshot.dialect()).isEqualTo(OpenAiCompatibleDialects.SILICONFLOW);
        assertThat(snapshot.endpoint()).hasToString("https://api.siliconflow.cn/v1");
        assertThat(snapshot.providerOptions()).containsEntry("endpoint_host", "api.siliconflow.cn");
        assertThat(option.providerDisplayName()).isEqualTo("硅基流动 SiliconFlow");
        assertThat(option.availability()).isEqualTo("AVAILABLE");
        assertThat(platform.catalog().profile(option.id()))
                .hasValueSatisfying(profile -> assertThat(profile.selectable()).isTrue());
    }

    @Test
    void freezesStandardChatCompletionsDialectForAnArbitraryProviderId() {
        var deepSeek = provider(
                "deepseek",
                "DeepSeek",
                true,
                URI.create("https://api.deepseek.com"),
                "env://DEEPSEEK_API_KEY",
                List.of(new PersonalAssistantProperties.ApiBinding(
                        "openai-chat-completions", "deepseek-openai-chat", null)),
                List.of(model("deepseek-v4-flash", "DeepSeek Flash", "deepseek-v4-flash", "openai-chat-completions")));
        var provider = provider(
                "third-party-openai",
                "Third-party OpenAI-compatible",
                true,
                URI.create("https://gateway.example.com/v1"),
                "env://THIRD_PARTY_API_KEY",
                List.of(new PersonalAssistantProperties.ApiBinding("openai-chat-completions", null, null)),
                List.of(model("third-party-chat", "Third-party Chat", "vendor-chat-model", "openai-chat-completions")));

        var platform = PersonalModelFactory.createPlatform(
                List.of(deepSeek, provider), "deepseek-v4-flash", new ObjectMapper(), shell());

        var snapshot = platform.contribution().snapshots().get("third-party-chat");
        assertThat(snapshot.providerId().value()).isEqualTo("third-party-openai");
        assertThat(snapshot.dialect()).isEqualTo("standard");
        assertThat(snapshot.nativeStreaming()).isTrue();
        assertThat(snapshot.providerOptions())
                .containsEntry("endpoint_host", "gateway.example.com")
                .doesNotContainKeys("thinking", "reasoning_effort");
        assertThat(platform.catalog().find("third-party-chat")).isEmpty();

        assertThatThrownBy(() -> PersonalModelFactory.createPlatform(
                        List.of(deepSeek, provider), "third-party-chat", new ObjectMapper(), shell()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("default Personal model profile is not verified");
    }

    @Test
    void freezesDeepSeekAnthropicBindingEndpointAndDisabledThinking() {
        var provider = provider(
                "deepseek",
                "DeepSeek",
                true,
                URI.create("https://api.deepseek.com"),
                "env://DEEPSEEK_API_KEY",
                List.of(new PersonalAssistantProperties.ApiBinding(
                        "anthropic-messages",
                        "deepseek-anthropic-messages",
                        URI.create("https://api.deepseek.com/anthropic"))),
                List.of(model(
                        "deepseek-anthropic-flash",
                        "DeepSeek Anthropic Messages Flash",
                        "deepseek-v4-flash",
                        "anthropic-messages")));

        var platform = PersonalModelFactory.createPlatform(
                List.of(provider), "deepseek-anthropic-flash", new ObjectMapper(), shell());
        var snapshot = platform.contribution().snapshot();

        assertThat(snapshot.apiStyle()).isEqualTo(ModelApiStyles.ANTHROPIC_MESSAGES);
        assertThat(snapshot.adapterType()).isEqualTo(ModelApiStyles.ANTHROPIC_MESSAGES_ADAPTER);
        assertThat(snapshot.endpoint()).hasToString("https://api.deepseek.com/anthropic");
        assertThat(snapshot.invocationOptions()).containsEntry("thinking", "disabled");
    }

    @Test
    void exposesVerifiedDeepSeekThinkingControlsAndRegistersEveryPreferenceProfile() {
        var provider = provider(
                "deepseek",
                "DeepSeek",
                true,
                URI.create("https://api.deepseek.com"),
                "env://DEEPSEEK_API_KEY",
                List.of(new PersonalAssistantProperties.ApiBinding(
                        "openai-chat-completions", OpenAiCompatibleDialects.DEEPSEEK, null)),
                List.of(model(
                        "deepseek-chat-pro",
                        "DeepSeek V4 Pro · Chat Completions",
                        "deepseek-v4-pro",
                        "openai-chat-completions",
                        ModelReasoningMode.ENABLED)));
        var platform = PersonalModelFactory.createPlatform(
                List.of(provider), "deepseek-chat-pro", new ObjectMapper(), shell());
        var option = platform.catalog().available().getFirst();

        assertThat(option.controls().responseMode().readOnly()).isFalse();
        assertThat(option.controls().responseMode().allowedValues())
                .containsExactly(
                        PersonalResponseMode.RECOMMENDED, PersonalResponseMode.FAST, PersonalResponseMode.DEEP);
        assertThat(option.controls().reasoningEffort().visible()).isTrue();
        assertThat(option.controls().reasoningEffort().allowedValues())
                .containsExactly(ModelReasoningEffort.HIGH, ModelReasoningEffort.MAX);
        assertThat(option.controls().responseMode().effectiveSummary()).isEqualTo("Thinking on · High");
        assertThat(platform.catalog().runProfiles()).hasSize(20);

        var recommended = platform.catalog().defaultSelection();
        assertThat(recommended.effectiveParameters().reasoning().mode()).isEqualTo(ModelReasoningMode.ENABLED);
        assertThat(recommended.effectiveParameters().reasoning().effort()).contains(ModelReasoningEffort.HIGH);

        var fast = platform.catalog()
                .resolve(new PersonalModelSelectionRequest(
                        option.id(),
                        option.preferenceSchemaVersion(),
                        option.profileVersion(),
                        option.profileDigest(),
                        new PersonalModelPreferences(
                                PersonalResponseMode.FAST,
                                java.util.Optional.empty(),
                                PersonalResponseLength.RECOMMENDED)));
        assertThat(fast.effectiveParameters().reasoning().mode()).isEqualTo(ModelReasoningMode.DISABLED);

        var deep = platform.catalog()
                .resolve(new PersonalModelSelectionRequest(
                        option.id(),
                        option.preferenceSchemaVersion(),
                        option.profileVersion(),
                        option.profileDigest(),
                        new PersonalModelPreferences(
                                PersonalResponseMode.DEEP,
                                java.util.Optional.of(ModelReasoningEffort.MAX),
                                PersonalResponseLength.LONG)));
        assertThat(deep.effectiveParameters().reasoning().mode()).isEqualTo(ModelReasoningMode.ENABLED);
        assertThat(deep.effectiveParameters().reasoning().effort()).contains(ModelReasoningEffort.MAX);
        assertThat(deep.effectiveParameters().maxOutputTokens()).isEqualTo(8_192);
    }

    @Test
    void keepsResponsesReasoningControlsReadOnlyUntilItsToggleContractIsVerified() {
        var provider = provider(
                "deepseek",
                "DeepSeek",
                true,
                URI.create("https://api.deepseek.com"),
                "env://DEEPSEEK_API_KEY",
                List.of(new PersonalAssistantProperties.ApiBinding(
                        "openai-responses", "deepseek-openai-responses", null)),
                List.of(model(
                        "deepseek-responses-pro",
                        "DeepSeek V4 Pro · Responses",
                        "deepseek-v4-pro",
                        "openai-responses",
                        ModelReasoningMode.ENABLED)));
        var platform = PersonalModelFactory.createPlatform(
                List.of(provider), "deepseek-responses-pro", new ObjectMapper(), shell());
        var option = platform.catalog().available().getFirst();

        assertThat(option.availability()).isEqualTo("AVAILABLE");
        assertThat(option.controls().responseMode().readOnly()).isTrue();
        assertThat(option.controls().responseMode().allowedValues()).containsExactly(PersonalResponseMode.RECOMMENDED);
        assertThat(option.controls().reasoningEffort().visible()).isFalse();
        assertThat(platform.catalog().runProfiles()).hasSize(4);
        assertThat(platform.catalog().runProfiles()).allSatisfy(selection -> {
            assertThat(selection.effectiveParameters().reasoning().mode()).isEqualTo(ModelReasoningMode.ENABLED);
            assertThat(selection.effectiveParameters().reasoning().effort()).contains(ModelReasoningEffort.HIGH);
        });
    }

    @Test
    void recommendsChatBindingConsistentlyForEveryStyleOfTheSameProviderModel() {
        var provider = provider(
                "deepseek",
                "DeepSeek",
                true,
                URI.create("https://api.deepseek.com"),
                "env://DEEPSEEK_API_KEY",
                List.of(
                        new PersonalAssistantProperties.ApiBinding(
                                "openai-responses", "deepseek-openai-responses", null),
                        new PersonalAssistantProperties.ApiBinding(
                                "anthropic-messages",
                                "deepseek-anthropic-messages",
                                URI.create("https://api.deepseek.com/anthropic")),
                        new PersonalAssistantProperties.ApiBinding(
                                "openai-chat-completions", OpenAiCompatibleDialects.DEEPSEEK, null)),
                List.of(
                        model(
                                "deepseek-responses-pro",
                                "DeepSeek V4 Pro · Responses",
                                "deepseek-v4-pro",
                                "openai-responses",
                                ModelReasoningMode.ENABLED),
                        model(
                                "deepseek-anthropic-pro",
                                "DeepSeek V4 Pro · Anthropic Messages",
                                "deepseek-v4-pro",
                                "anthropic-messages",
                                ModelReasoningMode.ENABLED),
                        model(
                                "deepseek-chat-pro",
                                "DeepSeek V4 Pro · Chat Completions",
                                "deepseek-v4-pro",
                                "openai-chat-completions",
                                ModelReasoningMode.ENABLED)));

        var options = PersonalModelFactory.createPlatform(
                        List.of(provider), "deepseek-chat-pro", new ObjectMapper(), shell())
                .catalog()
                .available();

        assertThat(options)
                .extracting(option -> option.controls().apiStyle().recommendedValue())
                .containsOnly("deepseek-chat-pro");
        assertThat(options)
                .allSatisfy(option -> assertThat(option.controls().apiStyle().allowedValues())
                        .containsExactly("deepseek-chat-pro", "deepseek-anthropic-pro", "deepseek-responses-pro"));
    }

    @Test
    void permitsInsecureHttpOnlyForExplicitLoopbackModelEndpoints() {
        var loopback = provider(
                "openai",
                "OpenAI",
                false,
                URI.create("http://localhost:30000/v1"),
                "env://OPENAI_API_KEY",
                List.of(new PersonalAssistantProperties.ApiBinding("openai-chat-completions", null, null)),
                List.of(model("openai-gpt-5.6-luna", "GPT-5.6 Luna", "gpt-5.6-luna", "openai-chat-completions")));
        var external = provider(
                "openai",
                "OpenAI",
                false,
                URI.create("http://example.com/v1"),
                "env://OPENAI_API_KEY",
                loopback.apiBindings(),
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

    private static PersonalAssistantProperties.ModelProvider provider(
            String id,
            String displayName,
            boolean nativeStreaming,
            URI endpoint,
            String credentialReference,
            List<PersonalAssistantProperties.ApiBinding> bindings,
            List<PersonalAssistantProperties.ProviderModel> models) {
        return new PersonalAssistantProperties.ModelProvider(
                id,
                displayName,
                "remote",
                false,
                nativeStreaming,
                endpoint,
                credentialReference,
                bindings,
                models,
                null);
    }

    private static PersonalAssistantProperties.ProviderModel model(
            String id, String displayName, String providerModelId, String style) {
        return model(id, displayName, providerModelId, style, ModelReasoningMode.DISABLED);
    }

    private static PersonalAssistantProperties.ProviderModel model(
            String id, String displayName, String providerModelId, String style, ModelReasoningMode reasoningMode) {
        return new PersonalAssistantProperties.ProviderModel(
                id,
                displayName,
                displayName,
                providerModelId,
                style,
                reasoningMode == ModelReasoningMode.DISABLED
                        ? Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING)
                        : Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING, ModelCapability.REASONING),
                reasoningMode,
                131_072,
                8_192);
    }

    private static PersonalAssistantProperties.ProviderModel textModel(
            String id, String displayName, String providerModelId, String style) {
        return new PersonalAssistantProperties.ProviderModel(
                id,
                displayName,
                displayName,
                providerModelId,
                style,
                Set.of(ModelCapability.TEXT_CHAT),
                ModelReasoningMode.DISABLED,
                131_072,
                8_192);
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

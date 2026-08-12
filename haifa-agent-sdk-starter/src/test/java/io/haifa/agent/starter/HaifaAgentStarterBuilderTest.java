package io.haifa.agent.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.diagnostics.PromptDiagnosticSource;
import io.haifa.agent.sdk.product.ProductCapabilities;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

public class HaifaAgentStarterBuilderTest {
    @Test
    void exposesModelNeutralStarterEntryPoints() {
        assertThat(HaifaAgentStarter.class.getDeclaredMethods())
                .filteredOn(method -> Modifier.isPublic(method.getModifiers()) && !method.isSynthetic())
                .extracting(Method::getName)
                .containsExactlyInAnyOrder("create", "builder");
        assertThat(HaifaAgentStarterBuilder.class.getSimpleName()).doesNotContain("DeepSeek");
    }

    @Test
    void buildsSafeDefaultDeepSeekV4FlashAssembly() {
        try (var agent = HaifaAgentStarter.builder()
                .environment(ignored -> "test-secret")
                .tool(new WeatherTool())
                .build()) {
            assertThat(agent.assembly().profile().runProfileId()).isEqualTo("deepseek-v4-flash");
            assertThat(agent.assembly().profile().instructions()).contains("helpful assistant");
            assertThat(agent.assembly().profile().allowedTools()).containsExactly("weather_get");
            assertThat(agent.assembly()
                            .profile()
                            .requirement(ProductCapabilities.MEMORY)
                            .mode()
                            .name())
                    .isEqualTo("NONE");
            assertThat(agent.assembly()
                            .profile()
                            .requirement(ProductCapabilities.EXECUTION)
                            .mode()
                            .name())
                    .isEqualTo("NONE");
        }
    }

    @Test
    void runsDeterministicHelloWorldThroughTheSameStarterAssembly() throws Exception {
        var model = (io.haifa.agent.model.api.AgentChatModel) request -> new AgentChatResponse(
                "starter-response",
                request.model().providerModelId(),
                "Hello from Haifa Agent!",
                List.of(),
                ModelFinishReason.STOP,
                ModelUsage.unpriced(4, 5),
                "",
                Map.of());
        try (var agent =
                HaifaAgentStarter.builder().model(model, testSnapshot()).build()) {
            var conversation =
                    agent.conversations().start(new StartConversationCommand("hello-1", "Hello Haifa", "Say hello."));
            var completed = agent.runs().await(conversation.activeRunId().orElseThrow());

            assertThat(completed.output()).contains("Hello from Haifa Agent!");
        }
    }

    @Test
    void exposesDisplayMetadataAndLightweightChatWithoutAddingMetadataToPrompt() throws Exception {
        AtomicReference<io.haifa.agent.model.api.AgentChatRequest> request = new AtomicReference<>();
        var model = (io.haifa.agent.model.api.AgentChatModel) value -> {
            request.set(value);
            return new AgentChatResponse(
                    "starter-response",
                    value.model().providerModelId(),
                    "Hello from the Chat facade!",
                    List.of(),
                    ModelFinishReason.STOP,
                    ModelUsage.unpriced(4, 5),
                    "",
                    Map.of());
        };
        try (var agent = HaifaAgentStarter.builder()
                .name("weather-agent")
                .description("Display-only weather helper")
                .instructions("Answer weather questions.")
                .model(model, testSnapshot())
                .build()) {
            var response = agent.chat("Hello").await();

            assertThat(response.text()).isEqualTo("Hello from the Chat facade!");
            assertThat(response.sessionId()).isNotNull();
            assertThat(response.runId()).isNotNull();
            assertThat(response.status().isTerminal()).isTrue();
            assertThat(response.error()).isEmpty();
            assertThat(agent.metadata().name()).isEqualTo("weather-agent");
            assertThat(agent.metadata().description()).isEqualTo("Display-only weather helper");
            assertThat(request.get().messages())
                    .extracting(io.haifa.agent.model.api.ModelMessage::content)
                    .noneMatch(text -> text.contains("weather-agent") || text.contains("Display-only weather helper"));

            var diagnostics = agent.runs().promptDiagnostics(response.runId());
            assertThat(diagnostics.available()).isTrue();
            assertThat(diagnostics.components())
                    .extracting("source")
                    .contains(
                            PromptDiagnosticSource.STARTER_INSTRUCTIONS,
                            PromptDiagnosticSource.RUNTIME_SAFETY,
                            PromptDiagnosticSource.SESSION_CONTEXT);
            assertThat(diagnostics.toString())
                    .doesNotContain("Answer weather questions.")
                    .doesNotContain("Hello")
                    .doesNotContain("Display-only weather helper");
        }
    }

    @Test
    void diagnosesOnlyTheStarterInstructionsFallback() {
        try (var fallback = HaifaAgentStarter.builder()
                        .model(fixedModel("ok"), testSnapshot())
                        .build();
                var explicit = HaifaAgentStarter.builder()
                        .instructions("Use explicit trusted instructions.")
                        .model(fixedModel("ok"), testSnapshot())
                        .build()) {
            assertThat(fallback.diagnostics()).extracting("code").contains("DEFAULT_INSTRUCTIONS_IN_USE");
            assertThat(explicit.diagnostics()).extracting("code").doesNotContain("DEFAULT_INSTRUCTIONS_IN_USE");
            assertThat(fallback.diagnostics().toString()).doesNotContain("helpful assistant");
        }
    }

    @Test
    void rejectsInvalidDisplayMetadata() {
        assertThatThrownBy(() -> HaifaAgentStarter.builder().name(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HaifaAgentStarter.builder().description("x".repeat(513)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lightweightChatPreservesSafeTerminalFailure() throws Exception {
        var model = (io.haifa.agent.model.api.AgentChatModel) request -> {
            throw new IllegalStateException("provider-secret-detail");
        };
        try (var agent =
                HaifaAgentStarter.builder().model(model, testSnapshot()).build()) {
            var response = agent.chat("Fail safely.").await();

            assertThat(response.status().isTerminal()).isTrue();
            assertThat(response.error()).isPresent();
            assertThat(response.toString()).doesNotContain("provider-secret-detail");
            assertThatThrownBy(response::text)
                    .isInstanceOf(io.haifa.agent.sdk.api.HaifaAgentException.class)
                    .hasMessage("CHAT_OUTPUT_UNAVAILABLE")
                    .hasMessageNotContaining("provider-secret-detail");
        }
    }

    @Test
    void registersMultipleProvidersAndSelectsThemByTrustedRunProfile() throws Exception {
        var first = fixedModel("first-provider");
        var second = fixedModel("second-provider");
        try (var agent = HaifaAgentStarter.builder()
                .model(first, testSnapshot("first-provider", "first-model", "first-adapter"))
                .model(second, testSnapshot("second-provider", "second-model", "second-adapter"))
                .defaultModel("first-model")
                .build()) {
            var defaultConversation = agent.conversations()
                    .start(new StartConversationCommand("multi-1", "Default", "Use the default model."));
            var selectedConversation = agent.conversations()
                    .start(new StartConversationCommand(
                            "multi-2", "Selected", "Use the selected model.", Optional.of("second-model")));

            assertThat(agent.runs()
                            .await(defaultConversation.activeRunId().orElseThrow())
                            .output())
                    .contains("first-provider");
            assertThat(agent.runs()
                            .await(selectedConversation.activeRunId().orElseThrow())
                            .output())
                    .contains("second-provider");
        }
    }

    @Test
    void rejectsAnUnknownDefaultModel() {
        assertThatThrownBy(() -> HaifaAgentStarter.builder()
                        .model(fixedModel("first"), testSnapshot("first", "first-model", "first-adapter"))
                        .defaultModel("missing")
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("default model");
    }

    @Test
    void runsTypedJavaToolThroughTheStarterRuntimePipeline() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicReference<WeatherRequest> invoked = new AtomicReference<>();
        var model = (io.haifa.agent.model.api.AgentChatModel) request -> {
            if (modelCalls.incrementAndGet() == 1) {
                assertThat(request.tools()).extracting("name").containsExactly("weather_get");
                return new AgentChatResponse(
                        "tool-response",
                        request.model().providerModelId(),
                        "",
                        List.of(new ModelToolCall(
                                new ProviderToolCallCorrelationId("weather-call-1"),
                                "weather_get",
                                Map.of("city", "Shanghai"))),
                        ModelFinishReason.TOOL_CALLS,
                        ModelUsage.unpriced(4, 2),
                        "",
                        Map.of());
            }
            return new AgentChatResponse(
                    "final-response",
                    request.model().providerModelId(),
                    "The weather is sunny.",
                    List.of(),
                    ModelFinishReason.STOP,
                    ModelUsage.unpriced(5, 3),
                    "",
                    Map.of());
        };
        try (var agent = HaifaAgentStarter.builder()
                .model(model, testSnapshot())
                .tool(new WeatherTool(invoked))
                .build()) {
            var conversation = agent.conversations()
                    .start(new StartConversationCommand("tool-1", "Weather", "Weather in Shanghai?"));
            var completed = agent.runs().await(conversation.activeRunId().orElseThrow());

            assertThat(completed.output()).contains("The weather is sunny.");
            assertThat(invoked.get()).isEqualTo(new WeatherRequest("Shanghai"));
            assertThat(modelCalls).hasValue(2);
        }
    }

    @Test
    void failsBeforeAssemblyWhenTheCredentialEnvironmentVariableIsMissing() {
        assertThatThrownBy(() ->
                        HaifaAgentStarter.builder().environment(ignored -> null).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DEEPSEEK_API_KEY is not configured")
                .hasMessageNotContaining("secret");
    }

    private static ResolvedModelSnapshot testSnapshot() {
        return testSnapshot("test", "starter-test", "test-adapter");
    }

    private static ResolvedModelSnapshot testSnapshot(String providerId, String modelId, String adapterType) {
        return ResolvedModelSnapshot.create(
                new ModelProviderId(providerId),
                "1.0.0",
                new ModelDefinitionId(modelId),
                "1.0.0",
                modelId,
                adapterType,
                "1.0.0",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                ModelApiBindingDefinition.STANDARD_DIALECT,
                URI.create("https://model.invalid"),
                new CredentialRef("env://TEST_KEY"),
                false,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                8_192,
                1_024,
                Map.of(),
                Map.of());
    }

    private static io.haifa.agent.model.api.AgentChatModel fixedModel(String answer) {
        return request -> new AgentChatResponse(
                answer,
                request.model().providerModelId(),
                answer,
                List.of(),
                ModelFinishReason.STOP,
                ModelUsage.unpriced(1, 1),
                "",
                Map.of());
    }

    public record WeatherRequest(String city) {}

    public record WeatherResponse(String forecast) {}

    private static final class WeatherTool implements JavaTool<WeatherRequest, WeatherResponse> {
        private static final JavaToolSpec<WeatherRequest, WeatherResponse> SPEC = JavaToolSpec.builder(
                        "weather.get", WeatherRequest.class, WeatherResponse.class)
                .alias("weather_get")
                .pure()
                .build();
        private final AtomicReference<WeatherRequest> invoked;

        private WeatherTool() {
            this(new AtomicReference<>());
        }

        private WeatherTool(AtomicReference<WeatherRequest> invoked) {
            this.invoked = invoked;
        }

        @Override
        public JavaToolSpec<WeatherRequest, WeatherResponse> spec() {
            return SPEC;
        }

        @Override
        public WeatherResponse invoke(WeatherRequest input, JavaToolContext context) {
            invoked.set(input);
            return new WeatherResponse("Sunny in " + input.city());
        }
    }
}

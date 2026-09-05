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
import io.haifa.agent.model.api.ModelErrorCategory;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelInvocationException;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedCredential;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.model.openai.OpenAiCompatibleModelConfiguration;
import io.haifa.agent.model.openai.OpenAiCompatibleModelConfiguration.Dialect;
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
    void buildsDeepSeekVisionAssemblyWithExactModelBinding() {
        assertThat(HaifaAgentStarterBuilder.SUPPORTED_DEEPSEEK_MODELS)
                .contains(HaifaAgentStarterBuilder.MODEL_ID, HaifaAgentStarterBuilder.VISION_MODEL_ID);
        assertThat(HaifaAgentStarterBuilder.DEEPSEEK_VISION_MODELS)
                .contains(HaifaAgentStarterBuilder.VISION_MODEL_ID)
                .doesNotContain(HaifaAgentStarterBuilder.MODEL_ID);

        try (var agent = HaifaAgentStarter.builder()
                .environment(ignored -> "test-secret")
                .defaultModel(HaifaAgentStarterBuilder.VISION_MODEL_ID)
                .build()) {
            assertThat(agent.assembly().profile().runProfileId()).isEqualTo(HaifaAgentStarterBuilder.VISION_MODEL_ID);
            var contribution = agent.assembly().contributions().get(ProductCapabilities.MODEL);
            assertThat(contribution).isNotNull();
            assertThat(contribution.publicSummary()).isEqualTo("DeepSeek Vision with Thinking disabled");
        }
    }

    @Test
    void rejectsUnsupportedDeepSeekModelId() {
        assertThatThrownBy(() -> HaifaAgentStarter.builder()
                        .environment(ignored -> "test-secret")
                        .defaultModel("unsupported-vision-model")
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported default DeepSeek model: unsupported-vision-model");
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
    void exposesDisplayNameAndLightweightChatWithoutAddingItToPrompt() throws Exception {
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
            assertThat(request.get().messages())
                    .extracting(io.haifa.agent.model.api.ModelMessage::content)
                    .noneMatch(text -> text.contains("weather-agent"));

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
                    .doesNotContain("weather-agent");
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
    void rejectsInvalidDisplayName() {
        assertThatThrownBy(() -> HaifaAgentStarter.builder().name(" ")).isInstanceOf(IllegalArgumentException.class);
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
    void acceptsTypedOpenAiCompatibleConfigurationWithoutManualAdapterOrSnapshotAssembly() {
        var configured = OpenAiCompatibleModelConfiguration.builder(reference -> new ResolvedCredential("test-secret"))
                .providerId("deepseek")
                .modelId("typed-deepseek")
                .providerModelId("deepseek-v4-pro")
                .dialect(Dialect.DEEPSEEK)
                .endpoint(URI.create("https://api.deepseek.com"))
                .credentialRef(new CredentialRef("env://DEEPSEEK_API_KEY"))
                .capabilities(Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING))
                .tokenLimits(1_048_576, 8_192)
                .requestTimeout(java.time.Duration.ofSeconds(75))
                .build();

        try (var agent = HaifaAgentStarter.builder().model(configured).build()) {
            assertThat(agent.assembly().profile().runProfileId()).isEqualTo("typed-deepseek");
            assertThat(configured.snapshot().invocationOptions()).containsEntry("thinking", "disabled");
            assertThat(configured.snapshot().providerOptions()).containsEntry("haifa_request_timeout_millis", 75_000L);
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
    void returnsSchemaValidatedRecordOnlyAfterAToolLoopReachesItsTerminalAnswer() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicReference<WeatherRequest> invoked = new AtomicReference<>();
        var model = (io.haifa.agent.model.api.AgentChatModel) request -> {
            assertThat(request.structuredOutput()).isPresent();
            if (modelCalls.incrementAndGet() == 1) {
                return new AgentChatResponse(
                        "tool-response",
                        request.model().providerModelId(),
                        "",
                        List.of(new ModelToolCall(
                                new ProviderToolCallCorrelationId("weather-call-structured"),
                                "weather_get",
                                Map.of("city", "Shanghai"))),
                        ModelFinishReason.TOOL_CALLS,
                        ModelUsage.unpriced(4, 2),
                        "",
                        Map.of());
            }
            Map<String, Object> output =
                    Map.of("city", "Shanghai", "days", 2, "activities", List.of("Bund walk", "Museum"));
            return new AgentChatResponse(
                    "structured-response",
                    request.model().providerModelId(),
                    "{\"city\":\"Shanghai\",\"days\":2,\"activities\":[\"Bund walk\",\"Museum\"]}",
                    List.of(),
                    ModelFinishReason.STOP,
                    ModelUsage.unpriced(5, 3),
                    "",
                    Map.of(),
                    Optional.empty(),
                    Optional.of(output));
        };
        try (var agent = HaifaAgentStarter.builder()
                .model(model, structuredSnapshot())
                .tool(new WeatherTool(invoked))
                .build()) {
            var response = agent.chat("Plan a two-day trip.", TripPlan.class).await();

            assertThat(response.value()).isEqualTo(new TripPlan("Shanghai", 2, List.of("Bund walk", "Museum")));
            assertThat(response.error()).isEmpty();
            assertThat(invoked.get()).isEqualTo(new WeatherRequest("Shanghai"));
            assertThat(modelCalls).hasValue(2);
            var persisted =
                    agent.runs().find(response.runId()).orElseThrow().result().orElseThrow();
            assertThat(persisted.structuredOutput()).containsEntry("days", 2);
            assertThat(persisted.outputSchemaId()).startsWith("java-record:");
        }
    }

    @Test
    void classifiesUnsupportedInvalidRefusedAndTruncatedStructuredOutput() throws Exception {
        assertStructuredFailure(
                request -> new AgentChatResponse(
                        "unsupported-should-not-run",
                        request.model().providerModelId(),
                        "{}",
                        List.of(),
                        ModelFinishReason.STOP,
                        ModelUsage.unpriced(1, 1),
                        "",
                        Map.of()),
                testSnapshot(),
                "MODEL_STRUCTURED_OUTPUT_UNSUPPORTED");

        assertStructuredFailure(
                request -> new AgentChatResponse(
                        "invalid",
                        request.model().providerModelId(),
                        "{\"city\":\"Shanghai\"}",
                        List.of(),
                        ModelFinishReason.STOP,
                        ModelUsage.unpriced(1, 1),
                        "",
                        Map.of(),
                        Optional.empty(),
                        Optional.of(Map.of("city", "Shanghai"))),
                structuredSnapshot(),
                "MODEL_STRUCTURED_OUTPUT_INVALID");

        assertStructuredFailure(
                request -> {
                    throw new ModelInvocationException(
                            ModelErrorCategory.CONTENT_REJECTED,
                            false,
                            200,
                            "refusal",
                            request.callId(),
                            "provider rejected the generated content",
                            null);
                },
                structuredSnapshot(),
                "MODEL_CONTENT_REJECTED");

        assertStructuredFailure(
                request -> new AgentChatResponse(
                        "truncated",
                        request.model().providerModelId(),
                        "{\"city\":\"Shanghai\"",
                        List.of(),
                        ModelFinishReason.LENGTH,
                        ModelUsage.unpriced(1, 1),
                        "",
                        Map.of()),
                structuredSnapshot(),
                "MODEL_OUTPUT_TRUNCATED");
    }

    private static void assertStructuredFailure(
            io.haifa.agent.model.api.AgentChatModel model, ResolvedModelSnapshot snapshot, String expectedError)
            throws Exception {
        try (var agent = HaifaAgentStarter.builder().model(model, snapshot).build()) {
            var response = agent.chat("Return a trip plan.", TripPlan.class).await();
            assertThat(response.error())
                    .get()
                    .extracting(error -> error.code().wireCode())
                    .isEqualTo(expectedError);
            assertThatThrownBy(response::value)
                    .isInstanceOf(io.haifa.agent.sdk.api.HaifaAgentException.class)
                    .hasMessage("STRUCTURED_OUTPUT_UNAVAILABLE");
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

    private static ResolvedModelSnapshot structuredSnapshot() {
        return ResolvedModelSnapshot.create(
                new ModelProviderId("test"),
                "1.0.0",
                new ModelDefinitionId("starter-structured-test"),
                "1.0.0",
                "starter-structured-test",
                "structured-test-adapter",
                "1.0.0",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                ModelApiBindingDefinition.STANDARD_DIALECT,
                URI.create("https://model.invalid"),
                new CredentialRef("env://TEST_KEY"),
                false,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING, ModelCapability.STRUCTURED_OUTPUT),
                8_192,
                1_024,
                Map.of(),
                Map.of());
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

    public record TripPlan(String city, int days, List<String> activities) {}

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

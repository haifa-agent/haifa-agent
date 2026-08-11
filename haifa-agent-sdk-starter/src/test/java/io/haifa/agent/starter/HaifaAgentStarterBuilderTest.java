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
import io.haifa.agent.sdk.product.ProductCapabilities;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.util.List;
import java.util.Map;
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
        return ResolvedModelSnapshot.create(
                new ModelProviderId("test"),
                "1.0.0",
                new ModelDefinitionId("starter-test"),
                "1.0.0",
                "starter-test",
                "test-adapter",
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

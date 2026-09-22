package io.haifa.agent.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatModel;
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
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseSubmission;
import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.sdk.api.HaifaAgentException;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Native MCP Client Developer Experience through the Pure Java Starter. */
public class HaifaAgentStarterMcpTest {
    private static final URI ENDPOINT = URI.create("https://partner.example.com/mcp");

    @Test
    void disclosesAllowlistedMcpToolsToTheModelUnderAStableNamePrefix() throws Exception {
        var mcp = new FakeMcpServer().serving("enterprise-search", "search_courses", "search_policies", "search_jobs");

        assertThat(disclosedTools(starter(mcp).mcpServer(search().readOnly())))
                .containsExactly("enterprise_search_courses", "enterprise_search_jobs", "enterprise_search_policies");
    }

    @Test
    void registersJavaToolsAndMcpToolsInOneFrozenCatalog() throws Exception {
        var mcp = new FakeMcpServer().serving("enterprise-search", "search_jobs");

        assertThat(disclosedTools(starter(mcp).tool(new WeatherTool()).mcpServer(jobs().readOnly())))
                .containsExactly("enterprise_search_jobs", "weather_get");
    }

    @Test
    void failsTheBuildWhenTwoMcpServersProduceTheSameToolName() {
        var mcp = new FakeMcpServer().serving("course-search", "search").serving("policy-search", "search");

        assertThatThrownBy(() -> starter(mcp)
                        .mcpServer(McpServerSpec.streamableHttp("course-search", ENDPOINT)
                                .allowTools("search")
                                .toolNamePrefix("shared")
                                .readOnly())
                        .mcpServer(McpServerSpec.streamableHttp(
                                        "policy-search", URI.create("https://policies.example.com/mcp"))
                                .allowTools("search")
                                .toolNamePrefix("shared")
                                .readOnly())
                        .build())
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("TOOL_ALIAS_CONFLICT");
    }

    @Test
    void failsTheBuildWhenAnMcpToolCollidesWithAJavaTool() {
        var mcp = new FakeMcpServer().serving("enterprise-search", "get");

        assertThatThrownBy(() -> starter(mcp)
                        .tool(new WeatherTool())
                        .mcpServer(McpServerSpec.streamableHttp("enterprise-search", ENDPOINT)
                                .allowTools("get")
                                .toolNamePrefix("weather")
                                .readOnly())
                        .build())
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("TOOL_ALIAS_CONFLICT");
    }

    @Test
    void failsClosedWhenARequiredServerIsUnavailable() {
        var mcp =
                new FakeMcpServer().serving("enterprise-search", "search_jobs").failing("enterprise-search");

        assertThatThrownBy(() ->
                        starter(mcp).mcpServer(jobs().readOnly().required()).build())
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("MCP_SERVER_UNAVAILABLE");
    }

    @Test
    void startsWithoutAnUnavailableOptionalServerAndReportsASafeDiagnostic() throws Exception {
        var mcp = new FakeMcpServer()
                .serving("enterprise-search", "search_jobs")
                .serving("legacy-search", "search_legacy")
                .failing("legacy-search");

        var builder = starter(mcp)
                .mcpServer(jobs().readOnly().required())
                .mcpServer(McpServerSpec.streamableHttp("legacy-search", URI.create("https://legacy.example.com/mcp"))
                        .allowTools("search_legacy")
                        .toolNamePrefix("legacy")
                        .readOnly()
                        .optional());

        AtomicReference<List<String>> disclosed = new AtomicReference<>();
        AtomicReference<List<String>> diagnostics = new AtomicReference<>();
        runOnce(
                builder,
                agent -> {
                    diagnostics.set(agent.diagnostics().stream()
                            .map(diagnostic -> diagnostic.code())
                            .toList());
                },
                disclosed);

        assertThat(disclosed.get()).containsExactly("enterprise_search_jobs");
        assertThat(diagnostics.get()).contains("MCP_SERVER_UNAVAILABLE");
    }

    @Test
    void releasesMcpConnectionsWhenTheAgentIsClosedAndToleratesRepeatedClose() {
        var mcp = new FakeMcpServer().serving("enterprise-search", "search_jobs");

        HaifaAgent agent = starter(mcp).mcpServer(jobs().readOnly()).build();
        assertThat(mcp.closedClients()).isZero();

        agent.close();
        agent.close();

        assertThat(mcp.openClients()).isEqualTo(1);
        assertThat(mcp.closedClients()).isEqualTo(1);
    }

    @Test
    void releasesMcpConnectionsWhenTheBuildItselfFails() {
        var mcp = new FakeMcpServer().serving("enterprise-search", "get");

        assertThatThrownBy(() -> starter(mcp)
                        .tool(new WeatherTool())
                        .mcpServer(McpServerSpec.streamableHttp("enterprise-search", ENDPOINT)
                                .allowTools("get")
                                .toolNamePrefix("weather")
                                .readOnly())
                        .build())
                .isInstanceOf(HaifaAgentException.class);

        assertThat(mcp.openClients()).isEqualTo(1);
        assertThat(mcp.closedClients()).isEqualTo(1);
    }

    @Test
    void runsAModelToolCallThroughTheRuntimeIntoTheRemoteMcpToolAndBackIntoTheAgentLoop() throws Exception {
        var mcp = new FakeMcpServer().serving("enterprise-search", "search_jobs");
        AtomicInteger modelCalls = new AtomicInteger();
        AgentChatModel model = request -> {
            if (modelCalls.incrementAndGet() == 1) {
                assertThat(request.tools()).extracting("name").containsExactly("enterprise_search_jobs");
                return new AgentChatResponse(
                        "tool-response",
                        request.model().providerModelId(),
                        "",
                        List.of(new ModelToolCall(
                                new ProviderToolCallCorrelationId("mcp-call-1"),
                                "enterprise_search_jobs",
                                Map.of("query", "hangzhou java agent"))),
                        ModelFinishReason.TOOL_CALLS,
                        ModelUsage.unpriced(4, 2),
                        "",
                        Map.of());
            }
            return new AgentChatResponse(
                    "final-response",
                    request.model().providerModelId(),
                    "Found 3 roles in Hangzhou.",
                    List.of(),
                    ModelFinishReason.STOP,
                    ModelUsage.unpriced(5, 3),
                    "",
                    Map.of());
        };

        try (var agent = HaifaAgentStarter.builder()
                .model(model, testSnapshot())
                .mcpClientFactory(mcp)
                .mcpServer(jobs().readOnly())
                .build()) {
            var conversation = agent.conversations()
                    .start(new StartConversationCommand("mcp-1", "Jobs", "Which AI Agent jobs are open in Hangzhou?"));
            approvePendingToolCall(agent, conversation.runId());
            var completed = agent.runs().await(conversation.runId());

            assertThat(completed.status())
                    .as(
                            "run failed with: %s",
                            completed.error().map(error -> error.message()).orElse("none"))
                    .isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(completed.output()).contains("Found 3 roles in Hangzhou.");
            assertThat(mcp.calls()).containsExactly("enterprise-search:search_jobs:hangzhou java agent");
            assertThat(modelCalls).hasValue(2);
        }
    }

    @Test
    void exposesAReadableSpecWithoutLeakingIntegrationInternals() {
        McpServerSpec spec = search().readOnly().required();

        assertThat(spec.name()).isEqualTo("enterprise-search");
        assertThat(spec.endpoint()).isEqualTo(ENDPOINT);
        assertThat(spec.toolNamePrefix()).isEqualTo("enterprise");
        assertThat(spec.requirement()).isEqualTo(McpServerRequirement.REQUIRED);
        assertThat(spec.allowedTools()).containsExactlyInAnyOrder("search_courses", "search_policies", "search_jobs");
        assertThat(spec.localToolName("search_jobs")).isEqualTo("enterprise_search_jobs");
        assertThat(spec.optional().requirement()).isEqualTo(McpServerRequirement.OPTIONAL);
        assertThat(spec.requirement())
                .as("configuration methods must not mutate the original spec")
                .isEqualTo(McpServerRequirement.REQUIRED);
        assertThat(spec.toString()).doesNotContain("McpServerDefinition");
    }

    @Test
    void defaultsTheToolNamePrefixToTheConnectionName() throws Exception {
        var mcp = new FakeMcpServer().serving("enterprise-search", "search_jobs");

        assertThat(disclosedTools(starter(mcp)
                        .mcpServer(McpServerSpec.streamableHttp("enterprise-search", ENDPOINT)
                                .allowTools("search_jobs")
                                .readOnly())))
                .containsExactly("enterprise_search_search_jobs");
    }

    @Test
    void rejectsPlainHttpEndpointsOutsideExplicitLoopbackDevelopment() {
        var mcp = new FakeMcpServer().serving("enterprise-search", "search_jobs");

        assertThatThrownBy(() -> starter(mcp)
                        .mcpServer(McpServerSpec.streamableHttp(
                                        "enterprise-search", URI.create("http://partner.example.com/mcp"))
                                .allowTools("search_jobs")
                                .readOnly())
                        .build())
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("MCP_SERVER_CONFIGURATION_INVALID");
    }

    private static McpServerSpec search() {
        return McpServerSpec.streamableHttp("enterprise-search", ENDPOINT)
                .allowTools("search_courses", "search_policies", "search_jobs")
                .toolNamePrefix("enterprise");
    }

    private static McpServerSpec jobs() {
        return McpServerSpec.streamableHttp("enterprise-search", ENDPOINT)
                .allowTools("search_jobs")
                .toolNamePrefix("enterprise");
    }

    private static HaifaAgentStarterBuilder starter(FakeMcpServer mcp) {
        return HaifaAgentStarter.builder()
                .environment(HaifaAgentStarterMcpTest::environment)
                .mcpClientFactory(mcp);
    }

    private static String environment(String name) {
        return switch (name) {
            case "DEEPSEEK_API_KEY" -> "test-secret";
            case "PARTNER_MCP_TOKEN" -> "partner-token";
            default -> null;
        };
    }

    /** Runs one turn and returns the Tool names the Runtime actually disclosed to the model. */
    private static List<String> disclosedTools(HaifaAgentStarterBuilder builder) throws Exception {
        AtomicReference<List<String>> disclosed = new AtomicReference<>();
        runOnce(builder, agent -> {}, disclosed);
        return disclosed.get();
    }

    private static void runOnce(
            HaifaAgentStarterBuilder builder,
            java.util.function.Consumer<HaifaAgent> inspection,
            AtomicReference<List<String>> disclosed)
            throws Exception {
        AgentChatModel model = request -> {
            disclosed.set(
                    request.tools().stream().map(tool -> tool.name()).sorted().toList());
            return new AgentChatResponse(
                    "probe-response",
                    request.model().providerModelId(),
                    "done",
                    List.of(),
                    ModelFinishReason.STOP,
                    ModelUsage.unpriced(1, 1),
                    "",
                    Map.of());
        };
        try (var agent = builder.model(model, testSnapshot()).build()) {
            inspection.accept(agent);
            var conversation =
                    agent.conversations().start(new StartConversationCommand("probe-1", "Probe", "List your tools."));
            agent.runs().await(conversation.runId());
        }
    }

    private static void approvePendingToolCall(HaifaAgent agent, AgentRunId runId) throws InterruptedException {
        for (int attempt = 0; attempt < 400; attempt++) {
            var pending = agent.runs().pendingInteraction(runId);
            if (pending.isPresent()) {
                var interaction = pending.orElseThrow();
                agent.runs()
                        .respond(new InteractionResponseSubmission(
                                new InteractionResponseId("mcp-approval-1"),
                                interaction.requestId(),
                                runId,
                                interaction.revision(),
                                InteractionAction.APPROVE,
                                List.of(),
                                "mcp-approval-key-1",
                                Instant.now()));
                return;
            }
            if (agent.runs()
                    .find(runId)
                    .map(snapshot -> snapshot.status().isTerminal())
                    .orElse(false)) {
                return;
            }
            Thread.sleep(25);
        }
        throw new IllegalStateException("no approval interaction appeared for the MCP Tool call");
    }

    private static ResolvedModelSnapshot testSnapshot() {
        return ResolvedModelSnapshot.create(
                new ModelProviderId("test"),
                "1.0.0",
                new ModelDefinitionId("starter-mcp-test"),
                "1.0.0",
                "starter-mcp-test",
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
        @Override
        public JavaToolSpec<WeatherRequest, WeatherResponse> spec() {
            return JavaToolSpec.builder("weather_get", WeatherRequest.class, WeatherResponse.class)
                    .description("Gets the weather for a city")
                    .pure()
                    .timeout(Duration.ofSeconds(5))
                    .build();
        }

        @Override
        public WeatherResponse invoke(WeatherRequest input, JavaToolContext context) {
            return new WeatherResponse("Sunny in " + input.city());
        }
    }
}

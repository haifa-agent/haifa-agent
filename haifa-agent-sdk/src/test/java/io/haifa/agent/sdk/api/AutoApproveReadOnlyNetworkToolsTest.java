package io.haifa.agent.sdk.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ApiStyleId;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelAdapterCoordinate;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.policy.api.PolicyPresets;
import io.haifa.agent.policy.core.DefaultPolicyDecisionService;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseSubmission;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.sdk.SdkTestFixtures;
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.contribution.PolicyPlatformContribution;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.tool.api.ToolName;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** End-to-end coverage of the SDK read-only network auto-approval manifest. */
public class AutoApproveReadOnlyNetworkToolsTest {

    @Test
    void customDenyStillPreventsExecutionOfAnExplicitlyListedTool() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        var preset = PolicyPresets.standardApproval();
        var rules = new java.util.ArrayList<>(preset.rules());
        rules.add(new io.haifa.agent.policy.api.PolicyRule(
                new io.haifa.agent.policy.api.PolicyRuleRef("test-network-deny", "1"),
                io.haifa.agent.policy.api.PolicyRuleSource.MANAGED,
                500,
                new io.haifa.agent.policy.api.PolicyRuleMatcher(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of("web_fetch"),
                        Optional.of("invoke"),
                        Optional.of("tool"),
                        Optional.empty(),
                        Set.of()),
                io.haifa.agent.policy.api.PolicyEffect.DENY,
                Optional.empty(),
                "TEST_CUSTOM_DENY",
                "Custom deny"));
        HaifaAgentBuilder builder = agentBuilder(
                queueModel(toolCall("web_fetch", Map.of("value", "https://example.com")), finalAnswer("denied")));
        builder.policy(new PolicyPlatformContribution(
                io.haifa.agent.policy.api.PolicyRuleSet.of(rules, preset.defaultRule(), preset.approvalMode()),
                new DefaultPolicyDecisionService()));
        builder.tool(new WebFetchTool(executions)).autoApproveReadOnlyNetworkTools(Set.of(new ToolName("web_fetch")));
        try (HaifaAgent agent = builder.build()) {
            var started = agent.conversations().start(new StartConversationCommand("start-deny", "Denied", "fetch it"));
            assertThat(agent.runs().await(started.runId()).status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(agent.runs().pendingInteraction(started.runId())).isEmpty();
            assertThat(agent.runs().toolCalls(started.runId()))
                    .singleElement()
                    .satisfies(call -> assertThat(call.status()).isEqualTo(ToolCallStatus.DENIED));
            assertThat(executions).hasValue(0);
        }
    }

    @Test
    void listedReadOnlyNetworkToolRunsWithoutInteraction() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        HaifaAgentBuilder builder = agentBuilder(
                queueModel(toolCall("web_fetch", Map.of("value", "https://example.com")), finalAnswer("fetched")));
        builder.tool(new WebFetchTool(executions)).autoApproveReadOnlyNetworkTools(Set.of(new ToolName("web_fetch")));

        try (HaifaAgent agent = builder.build()) {
            var started =
                    agent.conversations().start(new StartConversationCommand("start-listed", "Listed", "fetch it"));
            var snapshot = agent.runs().await(started.runId());

            assertThat(snapshot.status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(agent.runs().pendingInteraction(started.runId())).isEmpty();
            assertThat(executions).hasValue(1);
        }
    }

    @Test
    void unlistedNetworkToolStillAsksAndRunsOnceAfterApproval() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        HaifaAgentBuilder builder = agentBuilder(
                queueModel(toolCall("web_fetch", Map.of("value", "https://example.com")), finalAnswer("fetched")));
        builder.tool(new WebFetchTool(executions));

        try (HaifaAgent agent = builder.build()) {
            var started =
                    agent.conversations().start(new StartConversationCommand("start-unlisted", "Unlisted", "fetch it"));
            var interaction = awaitPending(agent, started.runId());

            assertThat(executions).hasValue(0);
            approve(agent, started.runId(), interaction);
            var snapshot = agent.runs().await(started.runId());

            assertThat(snapshot.status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(executions).hasValue(1);
        }
    }

    @Test
    void fileWriteAndMixedSideEffectToolsStillAsk() throws Exception {
        AtomicInteger executeCounts = new AtomicInteger();
        HaifaAgentBuilder fileWrite =
                agentBuilder(queueModel(toolCall("writer", Map.of("value", "a")), finalAnswer("wrote")));
        fileWrite.tool(new FileWriteTool(executeCounts));
        try (HaifaAgent agent = fileWrite.build()) {
            var started = agent.conversations().start(new StartConversationCommand("start-write", "Write", "write it"));
            awaitPending(agent, started.runId());
            assertThat(executeCounts).hasValue(0);
        }

        AtomicInteger mixedCounts = new AtomicInteger();
        HaifaAgentBuilder mixed =
                agentBuilder(queueModel(toolCall("mixed", Map.of("value", "a")), finalAnswer("mixed")));
        mixed.tool(new WebFetchTool(new AtomicInteger()))
                .tool(new MixedSideEffectTool(mixedCounts))
                .autoApproveReadOnlyNetworkTools(Set.of(new ToolName("web_fetch")));
        try (HaifaAgent agent = mixed.build()) {
            var started = agent.conversations().start(new StartConversationCommand("start-mixed", "Mixed", "mix it"));
            awaitPending(agent, started.runId());
            assertThat(mixedCounts).hasValue(0);
        }
    }

    private static HaifaAgentBuilder agentBuilder(AgentChatModel model) {
        return HaifaAgents.builder(SdkTestFixtures.profile("network-approval"))
                .model(modelContribution(model))
                .persistence(SdkTestFixtures.persistenceContribution())
                .conversation(SdkTestFixtures.conversationContribution())
                .policy(new PolicyPlatformContribution(
                        PolicyPresets.standardApproval(), new DefaultPolicyDecisionService()));
    }

    private static AgentChatModel queueModel(AgentChatResponse... responses) {
        Queue<AgentChatResponse> queue = new ArrayDeque<>(List.of(responses));
        return request -> queue.remove();
    }

    private static AgentChatResponse toolCall(String name, Map<String, Object> arguments) {
        return new AgentChatResponse(
                "response-tool",
                "test-chat",
                "",
                List.of(new ModelToolCall(new ProviderToolCallCorrelationId("corr-" + name), name, arguments)),
                ModelFinishReason.TOOL_CALLS,
                ModelUsage.unpriced(1, 1),
                "",
                Map.of());
    }

    private static AgentChatResponse finalAnswer(String content) {
        return new AgentChatResponse(
                "response-final",
                "test-chat",
                content,
                List.of(),
                ModelFinishReason.STOP,
                ModelUsage.unpriced(1, 1),
                "",
                Map.of());
    }

    private static ModelContribution modelContribution(AgentChatModel model) {
        ResolvedModelSnapshot snapshot = ResolvedModelSnapshot.create(
                new ModelProviderId("test"),
                "1.0",
                new ModelDefinitionId("test-chat"),
                "1.0",
                "test-chat",
                "test-adapter",
                "1.0",
                new ApiStyleId("test-style"),
                "standard",
                URI.create("https://model.invalid/v1"),
                new CredentialRef("credential:test"),
                true,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                8_192,
                1_024,
                Map.of(),
                Map.of());
        return new ModelContribution(
                Map.of(ModelAdapterCoordinate.from(snapshot), model),
                snapshot,
                Map.of(snapshot.modelId().value(), snapshot));
    }

    private static InteractionView awaitPending(HaifaAgent agent, AgentRunId runId) throws InterruptedException {
        for (int attempt = 0; attempt < 400; attempt++) {
            var pending = agent.runs().pendingInteraction(runId);
            if (pending.isPresent()) return pending.orElseThrow();
            if (agent.runs()
                    .find(runId)
                    .map(snapshot -> snapshot.status().isTerminal())
                    .orElse(false)) {
                throw new IllegalStateException("run terminated without an approval interaction");
            }
            Thread.sleep(25);
        }
        throw new IllegalStateException("no approval interaction appeared");
    }

    private static void approve(HaifaAgent agent, AgentRunId runId, InteractionView interaction) {
        agent.runs()
                .respond(new InteractionResponseSubmission(
                        new InteractionResponseId("approval-1"),
                        interaction.requestId(),
                        runId,
                        interaction.revision(),
                        InteractionAction.APPROVE,
                        List.of(),
                        "approval-key-1",
                        Instant.now()));
    }

    public record FetchRequest(String value) {}

    public record FetchResponse(String value) {}

    private static final class WebFetchTool implements JavaTool<FetchRequest, FetchResponse> {
        private final AtomicInteger executions;

        private WebFetchTool(AtomicInteger executions) {
            this.executions = executions;
        }

        @Override
        public JavaToolSpec<FetchRequest, FetchResponse> spec() {
            return JavaToolSpec.builder("web_fetch", FetchRequest.class, FetchResponse.class)
                    .description("Fetches a URL from an exact host")
                    .networkAccess("api.example.com")
                    .build();
        }

        @Override
        public FetchResponse invoke(FetchRequest input, JavaToolContext context) {
            executions.incrementAndGet();
            return new FetchResponse(input.value());
        }
    }

    private static final class FileWriteTool implements JavaTool<FetchRequest, FetchResponse> {
        private final AtomicInteger executions;

        private FileWriteTool(AtomicInteger executions) {
            this.executions = executions;
        }

        @Override
        public JavaToolSpec<FetchRequest, FetchResponse> spec() {
            return JavaToolSpec.builder("writer", FetchRequest.class, FetchResponse.class)
                    .description("Writes a file")
                    .sideEffects(ToolSideEffect.FILE_WRITE)
                    .build();
        }

        @Override
        public FetchResponse invoke(FetchRequest input, JavaToolContext context) {
            executions.incrementAndGet();
            return new FetchResponse(input.value());
        }
    }

    private static final class MixedSideEffectTool implements JavaTool<FetchRequest, FetchResponse> {
        private final AtomicInteger executions;

        private MixedSideEffectTool(AtomicInteger executions) {
            this.executions = executions;
        }

        @Override
        public JavaToolSpec<FetchRequest, FetchResponse> spec() {
            return JavaToolSpec.builder("mixed", FetchRequest.class, FetchResponse.class)
                    .description("Reads a host and writes a file")
                    .networkAccess("api.example.com")
                    .sideEffects(ToolSideEffect.FILE_WRITE, ToolSideEffect.NETWORK_ACCESS)
                    .build();
        }

        @Override
        public FetchResponse invoke(FetchRequest input, JavaToolContext context) {
            executions.incrementAndGet();
            return new FetchResponse(input.value());
        }
    }
}

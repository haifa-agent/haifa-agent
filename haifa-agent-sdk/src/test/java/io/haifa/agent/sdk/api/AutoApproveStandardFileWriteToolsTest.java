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

/** Real Run/Tool coverage without Provider traffic. */
public class AutoApproveStandardFileWriteToolsTest {
    @Test
    void listedJavaWriterCompletesOnceWithoutInteraction() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        var builder = agentBuilder(queueModel(toolCall("writer", Map.of("value", "a")), finalAnswer("done")))
                .tool(new FileWriteTool(writes))
                .autoApproveStandardFileWriteTools(Set.of(new ToolName("writer")));
        try (var agent = builder.build()) {
            var started = agent.conversations().start(new StartConversationCommand("listed", "Listed", "write"));
            assertThat(agent.runs()
                            .await(started.runId(), java.time.Duration.ofSeconds(10))
                            .orElseThrow()
                            .status())
                    .isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(agent.runs().pendingInteraction(started.runId())).isEmpty();
            assertThat(agent.runs().toolCalls(started.runId()))
                    .singleElement()
                    .satisfies(call -> assertThat(call.status()).isEqualTo(ToolCallStatus.COMPLETED));
            assertThat(writes).hasValue(1);
        }
    }

    @Test
    void unlistedJavaAndIntegrationWritersAskAndExecuteOnceOnApproval() throws Exception {
        for (boolean integration : List.of(false, true)) {
            AtomicInteger writes = new AtomicInteger();
            var builder = agentBuilder(queueModel(toolCall("writer", Map.of("value", "a")), finalAnswer("done")));
            if (integration) builder.toolRegistrations(List.of(integrationWriter(writes)));
            else builder.tool(new FileWriteTool(writes));
            try (var agent = builder.build()) {
                var started =
                        agent.conversations().start(new StartConversationCommand("unlisted", "Unlisted", "write"));
                var pending = awaitPending(agent, started.runId());
                assertThat(agent.runs().find(started.runId()).orElseThrow().status())
                        .isEqualTo(AgentRunStatus.WAITING_APPROVAL);
                assertThat(writes).hasValue(0);
                approve(agent, started.runId(), pending);
                assertThat(agent.runs()
                                .await(started.runId(), java.time.Duration.ofSeconds(10))
                                .orElseThrow()
                                .status())
                        .isEqualTo(AgentRunStatus.COMPLETED);
                assertThat(writes).hasValue(1);
            }
        }
    }

    @Test
    void customAskAndDenyPreserveTheirRunEffects() throws Exception {
        for (var effect :
                List.of(io.haifa.agent.policy.api.PolicyEffect.ASK, io.haifa.agent.policy.api.PolicyEffect.DENY)) {
            AtomicInteger writes = new AtomicInteger();
            var preset = PolicyPresets.standardApproval();
            var rules = new java.util.ArrayList<>(preset.rules());
            rules.add(new io.haifa.agent.policy.api.PolicyRule(
                    new io.haifa.agent.policy.api.PolicyRuleRef("custom-file-write", "1"),
                    io.haifa.agent.policy.api.PolicyRuleSource.MANAGED,
                    500,
                    new io.haifa.agent.policy.api.PolicyRuleMatcher(
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.of("writer"),
                            Optional.of("invoke"),
                            Optional.of("tool"),
                            Optional.empty(),
                            Set.of()),
                    effect,
                    effect == io.haifa.agent.policy.api.PolicyEffect.ASK
                            ? Optional.of(io.haifa.agent.policy.api.PolicyChallenge.APPROVAL)
                            : Optional.empty(),
                    "CUSTOM_WRITE",
                    "Custom write policy"));
            var builder = agentBuilder(queueModel(toolCall("writer", Map.of("value", "a")), finalAnswer("done")))
                    .tool(new FileWriteTool(writes))
                    .autoApproveStandardFileWriteTools(Set.of(new ToolName("writer")))
                    .policy(new PolicyPlatformContribution(
                            io.haifa.agent.policy.api.PolicyRuleSet.of(
                                    rules, preset.defaultRule(), preset.approvalMode()),
                            new DefaultPolicyDecisionService()));
            try (var agent = builder.build()) {
                var started = agent.conversations().start(new StartConversationCommand("custom", "Custom", "write"));
                if (effect == io.haifa.agent.policy.api.PolicyEffect.ASK) {
                    var pending = awaitPending(agent, started.runId());
                    assertThat(writes).hasValue(0);
                    approve(agent, started.runId(), pending);
                }
                assertThat(agent.runs()
                                .await(started.runId(), java.time.Duration.ofSeconds(10))
                                .orElseThrow()
                                .status())
                        .isEqualTo(AgentRunStatus.COMPLETED);
                assertThat(writes).hasValue(effect == io.haifa.agent.policy.api.PolicyEffect.ASK ? 1 : 0);
                if (effect == io.haifa.agent.policy.api.PolicyEffect.DENY) {
                    assertThat(agent.runs().toolCalls(started.runId()))
                            .singleElement()
                            .satisfies(call -> assertThat(call.status()).isEqualTo(ToolCallStatus.DENIED));
                }
            }
        }
    }

    @Test
    void fileAndNetworkListsComposeInSameBuilderButMixedWriterStillAsks() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        var builder = agentBuilder(queueModel(
                        toolCall("writer", Map.of("value", "a")),
                        toolCall("web_fetch", Map.of("value", "a")),
                        finalAnswer("done")))
                .tool(new FileWriteTool(writes))
                .tool(new WebFetchTool(reads))
                .autoApproveStandardFileWriteTools(Set.of(new ToolName("writer")))
                .autoApproveReadOnlyNetworkTools(Set.of(new ToolName("web_fetch")));
        try (var agent = builder.build()) {
            var started = agent.conversations().start(new StartConversationCommand("both", "Both", "write and fetch"));
            assertThat(agent.runs()
                            .await(started.runId(), java.time.Duration.ofSeconds(10))
                            .orElseThrow()
                            .status())
                    .isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(agent.runs().pendingInteraction(started.runId())).isEmpty();
            assertThat(writes).hasValue(1);
            assertThat(reads).hasValue(1);
            assertThat(agent.runs().toolCalls(started.runId())).hasSize(2);
        }
        AtomicInteger mixedWrites = new AtomicInteger();
        var mixed = agentBuilder(queueModel(toolCall("mixed", Map.of("value", "a")), finalAnswer("done")))
                .tool(new FileWriteTool(new AtomicInteger()))
                .tool(new WebFetchTool(new AtomicInteger()))
                .tool(new MixedSideEffectTool(mixedWrites))
                .autoApproveStandardFileWriteTools(Set.of(new ToolName("writer")))
                .autoApproveReadOnlyNetworkTools(Set.of(new ToolName("web_fetch")));
        try (var agent = mixed.build()) {
            var started =
                    agent.conversations().start(new StartConversationCommand("mixed", "Mixed", "write and fetch"));
            awaitPending(agent, started.runId());
            assertThat(mixedWrites).hasValue(0);
        }
    }

    @Test
    void builderRejectsMissingPolicyOrToolsAndIntegrationNameSubstitution() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> HaifaAgents.builder(SdkTestFixtures.profile("missing-policy"))
                                .model(modelContribution(queueModel()))
                                .persistence(SdkTestFixtures.persistenceContribution())
                                .conversation(SdkTestFixtures.conversationContribution())
                                .tool(new FileWriteTool(new AtomicInteger()))
                                .autoApproveStandardFileWriteTools(Set.of(new ToolName("writer")))
                                .build())
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("FILE_WRITE_AUTO_APPROVE_POLICY_REQUIRED");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> agentBuilder(queueModel())
                        .autoApproveStandardFileWriteTools(Set.of(new ToolName("missing")))
                        .build())
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("FILE_WRITE_AUTO_APPROVE_TOOL_UNAVAILABLE");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> agentBuilder(queueModel())
                        .toolRegistrations(List.of(integrationWriter(new AtomicInteger())))
                        .autoApproveStandardFileWriteTools(Set.of(new ToolName("writer")))
                        .build())
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("FILE_WRITE_AUTO_APPROVE_TOOL_NOT_JAVA");
    }

    private static io.haifa.agent.sdk.contribution.ToolRegistration integrationWriter(AtomicInteger writes) {
        var prepared = io.haifa.agent.sdk.internal.ToolAssembly.prepare(
                null, List.of(new FileWriteTool(new AtomicInteger())), List.of());
        var binding = prepared.platform().catalog().snapshot().bindings().getFirst();
        var provider = new io.haifa.agent.tool.api.ToolProvider() {
            @Override
            public io.haifa.agent.tool.api.ToolProviderId id() {
                return binding.definition().providerId();
            }

            @Override
            public io.haifa.agent.core.tool.ToolResult invoke(io.haifa.agent.tool.api.ToolInvocationRequest request) {
                writes.incrementAndGet();
                return new io.haifa.agent.core.tool.ToolResult(
                        true, "ok", Map.of("value", "a"), List.of(), List.of(), false);
            }
        };
        // Deliberately reuses Java-looking definition metadata. Registration origin must still win.
        return new io.haifa.agent.sdk.contribution.ToolRegistration(
                binding.alias(), binding.definition(), "integration:writer", provider);
    }

    private static HaifaAgentBuilder agentBuilder(AgentChatModel model) {
        return HaifaAgents.builder(SdkTestFixtures.profile("file-write-approval"))
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

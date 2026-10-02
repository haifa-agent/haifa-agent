package io.haifa.agent.sdk.api;

import static io.haifa.agent.sdk.SdkTestFixtures.approve;
import static io.haifa.agent.sdk.SdkTestFixtures.awaitPending;
import static io.haifa.agent.sdk.SdkTestFixtures.finalAnswer;
import static io.haifa.agent.sdk.SdkTestFixtures.modelContribution;
import static io.haifa.agent.sdk.SdkTestFixtures.queueModel;
import static io.haifa.agent.sdk.SdkTestFixtures.toolCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.policy.api.PolicyPresets;
import io.haifa.agent.policy.core.DefaultPolicyDecisionService;
import io.haifa.agent.sdk.SdkTestFixtures;
import io.haifa.agent.sdk.SdkTestFixtures.FileWriteTool;
import io.haifa.agent.sdk.SdkTestFixtures.MixedSideEffectTool;
import io.haifa.agent.sdk.SdkTestFixtures.WebFetchTool;
import io.haifa.agent.sdk.contribution.PolicyPlatformContribution;
import io.haifa.agent.sdk.contribution.ToolRegistration;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.internal.ToolAssembly;
import io.haifa.agent.tool.api.ToolName;
import io.haifa.agent.tool.api.ToolProvider;
import io.haifa.agent.tool.api.ToolProviderId;
import java.util.List;
import java.util.Map;
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
        assertThatThrownBy(() -> HaifaAgents.builder(SdkTestFixtures.profile("missing-policy"))
                        .model(modelContribution(queueModel()))
                        .persistence(SdkTestFixtures.persistenceContribution())
                        .conversation(SdkTestFixtures.conversationContribution())
                        .tool(new FileWriteTool(new AtomicInteger()))
                        .autoApproveStandardFileWriteTools(Set.of(new ToolName("writer")))
                        .build())
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("FILE_WRITE_AUTO_APPROVE_POLICY_REQUIRED");
        assertThatThrownBy(() -> agentBuilder(queueModel())
                        .autoApproveStandardFileWriteTools(Set.of(new ToolName("missing")))
                        .build())
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("FILE_WRITE_AUTO_APPROVE_TOOL_UNAVAILABLE");
        assertThatThrownBy(() -> agentBuilder(queueModel())
                        .toolRegistrations(List.of(integrationWriter(new AtomicInteger())))
                        .autoApproveStandardFileWriteTools(Set.of(new ToolName("writer")))
                        .build())
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("FILE_WRITE_AUTO_APPROVE_TOOL_NOT_JAVA");
    }

    private static ToolRegistration integrationWriter(AtomicInteger writes) {
        var prepared = ToolAssembly.prepare(null, List.of(new FileWriteTool(new AtomicInteger())), List.of());
        var binding = prepared.platform().catalog().snapshot().bindings().getFirst();
        var provider = new ToolProvider() {
            @Override
            public ToolProviderId id() {
                return binding.definition().providerId();
            }

            @Override
            public io.haifa.agent.core.tool.ToolResult invoke(io.haifa.agent.tool.api.ToolInvocationRequest request) {
                writes.incrementAndGet();
                return new io.haifa.agent.core.tool.ToolResult(
                        true, "ok", Map.of("value", "a"), List.of(), List.of(), false);
            }
        };
        return new ToolRegistration(binding.alias(), binding.definition(), "integration:writer", provider);
    }

    private static HaifaAgentBuilder agentBuilder(AgentChatModel model) {
        return HaifaAgents.builder(SdkTestFixtures.profile("file-write-approval"))
                .model(modelContribution(model))
                .persistence(SdkTestFixtures.persistenceContribution())
                .conversation(SdkTestFixtures.conversationContribution())
                .policy(new PolicyPlatformContribution(
                        PolicyPresets.standardApproval(), new DefaultPolicyDecisionService()));
    }
}

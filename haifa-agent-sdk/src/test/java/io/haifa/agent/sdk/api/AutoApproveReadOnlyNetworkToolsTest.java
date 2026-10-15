package io.haifa.agent.sdk.api;

import static io.haifa.agent.sdk.SdkTestFixtures.approve;
import static io.haifa.agent.sdk.SdkTestFixtures.awaitPending;
import static io.haifa.agent.sdk.SdkTestFixtures.finalAnswer;
import static io.haifa.agent.sdk.SdkTestFixtures.modelContribution;
import static io.haifa.agent.sdk.SdkTestFixtures.queueModel;
import static io.haifa.agent.sdk.SdkTestFixtures.toolCall;
import static org.assertj.core.api.Assertions.assertThat;

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
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.tool.api.ToolName;
import java.util.Map;
import java.util.Optional;
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
}

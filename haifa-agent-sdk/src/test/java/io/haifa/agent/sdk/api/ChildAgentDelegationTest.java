package io.haifa.agent.sdk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ApiStyleId;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelAdapterCoordinate;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.policy.api.PolicyPresets;
import io.haifa.agent.policy.core.DefaultPolicyDecisionService;
import io.haifa.agent.runtime.api.ChildRunView;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.agent.sdk.contribution.InMemoryConversationContribution;
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.contribution.PolicyPlatformContribution;
import io.haifa.agent.sdk.contribution.SdkContributions;
import io.haifa.agent.sdk.conversation.ConversationQuery;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.product.ChildAgentSpec;
import io.haifa.agent.sdk.product.ProductId;
import io.haifa.agent.sdk.product.ProductProfile;
import io.haifa.agent.sdk.product.ProductRunProfile;
import io.haifa.agent.sdk.product.ProductRunProfileRef;
import io.haifa.agent.sdk.product.ProductVersion;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/** Public SDK surface for delegation: register children, delegate, list children, read child events. */
class ChildAgentDelegationTest {
    private static final ResolvedModelSnapshot PARENT_MODEL = snapshot("parent-chat");
    private static final ResolvedModelSnapshot CHILD_MODEL = snapshot("child-chat");

    @Test
    void parentDelegatesToRegisteredChildrenThroughThePublicSdkSurface() throws Exception {
        Map<String, String> modelByObjective = new ConcurrentHashMap<>();
        AgentChatModel model = request -> {
            boolean parent =
                    request.tools().stream().map(ModelToolSpecification::name).anyMatch("task"::equals);
            if (parent) {
                boolean results = request.messages().stream().anyMatch(m -> m.role() == ModelMessageRole.TOOL);
                if (results) return answer("combined findings");
                return new AgentChatResponse(
                        "r",
                        "stub",
                        "",
                        List.of(call("a", "researcher", "find sources"), call("b", "summarizer", "summarize context")),
                        ModelFinishReason.TOOL_CALLS,
                        ModelUsage.unpriced(1, 1),
                        "",
                        Map.of());
            }
            String objective = request.messages().stream()
                    .filter(m -> m.role() == ModelMessageRole.USER)
                    .findFirst()
                    .orElseThrow()
                    .content();
            modelByObjective.put(objective, request.model().modelId().value());
            return answer("child result for " + objective);
        };
        ProductRunProfile childProfile = new ProductRunProfile(
                "child-profile",
                "1.0.0",
                CHILD_MODEL.modelId().value(),
                AgentRunType.CHAT,
                budget(),
                new AgentRunLimits(8, 1, 1, 30_000, 30_000, 8, 8, 0),
                Map.of());

        try (HaifaAgent agent = HaifaAgents.builder(
                        profile().withAllowedChildAgents(Set.of("researcher", "summarizer")))
                .model(new ModelContribution(
                        Map.of(ModelAdapterCoordinate.from(PARENT_MODEL), model),
                        PARENT_MODEL,
                        Map.of(
                                PARENT_MODEL.modelId().value(), PARENT_MODEL,
                                CHILD_MODEL.modelId().value(), CHILD_MODEL)))
                .persistence(SdkContributions.inMemoryPersistence())
                .conversation(new InMemoryConversationContribution())
                .policy(new PolicyPlatformContribution(
                        PolicyPresets.standardApproval(), new DefaultPolicyDecisionService()))
                .runProfile(childProfile)
                .childAgent(new ChildAgentSpec(
                        "researcher",
                        "Finds and verifies sources",
                        "Research carefully and cite evidence.",
                        Optional.of(new ProductRunProfileRef("child-profile", "1.0.0")),
                        Set.of()))
                .childAgent(
                        ChildAgentSpec.of("summarizer", "Summarizes provided context", "Summarize briefly.", Set.of()))
                .build()) {
            var started = agent.conversations().start(new StartConversationCommand("start", "Parent chat", "Go"));
            var parent =
                    agent.runs().await(started.runId(), Duration.ofSeconds(20)).orElseThrow();

            assertThat(parent.status()).isEqualTo(AgentRunStatus.COMPLETED);
            List<ChildRunView> children = agent.runs().children(parent.runId());
            assertThat(children).hasSize(2).allSatisfy(child -> {
                assertThat(child.status()).isEqualTo(AgentRunStatus.COMPLETED);
                assertThat(child.startedAt()).isPresent();
                assertThat(child.completedAt()).isPresent();
            });
            assertThat(children)
                    .extracting(ChildRunView::objective)
                    .containsExactlyInAnyOrder("find sources", "summarize context");

            // D5: a referenced run profile selects the child model; otherwise the child inherits the parent's.
            assertThat(modelByObjective)
                    .containsEntry("find sources", CHILD_MODEL.modelId().value())
                    .containsEntry("summarize context", PARENT_MODEL.modelId().value());

            // D4: the parent's usage only counts its children.
            assertThat(parent.usage().childRuns()).isEqualTo(2);

            // Child lifecycle is visible in the parent's event stream; child events are readable by child ID.
            var events = agent.runs().events(parent.runId(), RunEventCursor.beforeFirst(parent.runId()), 500);
            assertThat(events.items())
                    .filteredOn(event -> event.eventType().equals("child.run.completed"))
                    .extracting(event -> ((RunEventPayloads.ChildRunLifecycle) event.payload()).childRunId())
                    .containsExactlyInAnyOrderElementsOf(children.stream()
                            .map(child -> child.runId().value())
                            .toList());
            ChildRunView child = children.getFirst();
            assertThat(agent.runs()
                            .events(child.runId(), RunEventCursor.beforeFirst(child.runId()), 500)
                            .items())
                    .isNotEmpty();

            // D6: only the parent conversation is listed; child sessions are reached through the parent run.
            assertThat(agent.conversations().list(ConversationQuery.active(20)).items())
                    .singleElement()
                    .satisfies(record -> assertThat(record.sessionId())
                            .isEqualTo(started.record().sessionId()));
            var childSession = agent.runs().view(child.runId()).orElseThrow().sessionId();
            assertThat(childSession).isNotEqualTo(started.record().sessionId());
            assertThat(agent.conversations().find(childSession)).isEmpty();
        }
    }

    @Test
    void buildFailsClosedForUnregisteredChildrenAndUnDelegableTools() {
        assertThatThrownBy(() -> builder(profile().withAllowedChildAgents(Set.of("missing")))
                        .build())
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("CHILD_AGENT_UNAVAILABLE");
        assertThatThrownBy(() -> builder(profile().withAllowedChildAgents(Set.of("writer")))
                        .childAgent(ChildAgentSpec.of("writer", "Writes", "Write.", Set.of("file_write")))
                        .build())
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("CHILD_AGENT_TOOL_UNAVAILABLE");
        assertThatThrownBy(() -> builder(profile().withAllowedChildAgents(Set.of("writer")))
                        .childAgent(new ChildAgentSpec(
                                "writer",
                                "Writes",
                                "Write.",
                                Optional.of(new ProductRunProfileRef("absent", "1.0.0")),
                                Set.of()))
                        .build())
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("CHILD_RUN_PROFILE_UNAVAILABLE");
        assertThatThrownBy(() -> ChildAgentSpec.of("Bad Id", "d", "i", Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static HaifaAgentBuilder builder(ProductProfile profile) {
        AgentChatModel model = request -> answer("unused");
        return HaifaAgents.builder(profile)
                .model(new ModelContribution(
                        Map.of(ModelAdapterCoordinate.from(PARENT_MODEL), model),
                        PARENT_MODEL,
                        Map.of(PARENT_MODEL.modelId().value(), PARENT_MODEL)))
                .persistence(SdkContributions.inMemoryPersistence())
                .conversation(new InMemoryConversationContribution());
    }

    private static ProductProfile profile() {
        return ProductProfile.create(
                new ProductId("delegation-product"),
                new ProductVersion("1.0.0"),
                new AgentDefinitionId("lead-agent"),
                new AgentDefinitionVersion(1, 0, 0),
                "Delegate independent research to child agents, then combine their findings.",
                new ProductRunProfileRef("delegation-chat", "1.0.0"),
                budget(),
                new AgentRunLimits(8, 1, 2, 30_000, 30_000, 8, 8, 4),
                Set.of(),
                Set.of());
    }

    private static AgentRunBudget budget() {
        return new AgentRunBudget(100_000, 100_000, 100_000, 8, 8, 4, "USD", 1_000);
    }

    private static ModelToolCall call(String correlation, String agent, String objective) {
        return new ModelToolCall(
                new ProviderToolCallCorrelationId("task-" + correlation),
                "task",
                Map.of("agent", agent, "objective", objective));
    }

    private static AgentChatResponse answer(String text) {
        return new AgentChatResponse(
                "r", "stub", text, List.of(), ModelFinishReason.STOP, ModelUsage.unpriced(1, 1), "", Map.of());
    }

    private static ResolvedModelSnapshot snapshot(String modelId) {
        return ResolvedModelSnapshot.create(
                new ModelProviderId("test"),
                "1.0",
                new ModelDefinitionId(modelId),
                "1.0",
                modelId,
                "test-adapter",
                "1.0",
                new ApiStyleId("test-style"),
                "standard",
                URI.create("https://model.invalid/v1"),
                new CredentialRef("credential:test"),
                true,
                Set.of(ModelCapability.TEXT_CHAT),
                8_192,
                1_024,
                Map.of(),
                Map.of());
    }
}

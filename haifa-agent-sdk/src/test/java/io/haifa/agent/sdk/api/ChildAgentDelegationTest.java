package io.haifa.agent.sdk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.error.AgentErrorCode;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.run.AgentRunType;
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
import io.haifa.agent.sdk.SdkTestFixtures;
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
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Public SDK surface for delegation: register children, delegate, list children, read child events. */
public class ChildAgentDelegationTest {
    private static final ResolvedModelSnapshot PARENT_MODEL = snapshot("parent-chat");

    @Test
    void parentDelegatesToRegisteredChildrenThroughThePublicSdkSurface() throws Exception {
        delegateLongDefinition(65_536, true);
    }

    @Test
    void longTrustedInstructionsStillRespectTheSelectedModelContextBudget() throws Exception {
        delegateLongDefinition(8_192, false);
    }

    private void delegateLongDefinition(int contextWindow, boolean contextFits) throws Exception {
        String researcherId = "1-" + "researcher".repeat(40);
        ResolvedModelSnapshot childModel = snapshot("child-chat", contextWindow);
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
                        List.of(call("a", researcherId, "find sources"), call("b", "summarizer", "summarize context")),
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
                childModel.modelId().value(),
                AgentRunType.CHAT,
                budget(),
                new AgentRunLimits(8, 1, 1, 30_000, 30_000, 8, 8, 0),
                Map.of());

        try (HaifaAgent agent = HaifaAgents.builder(
                        profile().withAllowedChildAgents(Set.of(researcherId, "summarizer")))
                .model(new ModelContribution(
                        Map.of(ModelAdapterCoordinate.from(PARENT_MODEL), model),
                        PARENT_MODEL,
                        Map.of(
                                PARENT_MODEL.modelId().value(), PARENT_MODEL,
                                childModel.modelId().value(), childModel)))
                .persistence(SdkContributions.inMemoryPersistence())
                .conversation(new InMemoryConversationContribution())
                .policy(new PolicyPlatformContribution(
                        PolicyPresets.standardApproval(), new DefaultPolicyDecisionService()))
                .runProfile(childProfile)
                .childAgent(new ChildAgentSpec(
                        researcherId,
                        "Finds and verifies sources. ".repeat(80),
                        "Research carefully and cite evidence. ".repeat(1_000),
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
            if (!contextFits) {
                ChildRunView rejected = children.stream()
                        .filter(child -> child.agentDefinitionId().value().equals(researcherId))
                        .findFirst()
                        .orElseThrow();
                var rejectedRun = agent.runs().find(rejected.runId()).orElseThrow();
                assertThat(rejected.status()).isEqualTo(AgentRunStatus.FAILED);
                assertThat(rejectedRun.error()).hasValueSatisfying(error -> {
                    assertThat(error.code()).isEqualTo(AgentErrorCode.MODEL_CONTEXT_TOO_LONG);
                });
                assertThat(rejectedRun.usage().modelCalls()).isZero();
                assertThat(modelByObjective).doesNotContainKey("find sources");
                assertThat(children)
                        .filteredOn(child -> child.agentDefinitionId().value().equals("summarizer"))
                        .singleElement()
                        .satisfies(child -> assertThat(child.status()).isEqualTo(AgentRunStatus.COMPLETED));
                return;
            }
            assertThat(children).hasSize(2).allSatisfy(child -> {
                assertThat(child.status()).isEqualTo(AgentRunStatus.COMPLETED);
                assertThat(child.startedAt()).isPresent();
                assertThat(child.completedAt()).isPresent();
            });
            assertThat(children)
                    .extracting(child -> child.agentDefinitionId().value())
                    .containsExactlyInAnyOrder(researcherId, "summarizer");
            assertThat(children)
                    .extracting(ChildRunView::objective)
                    .containsExactlyInAnyOrder("find sources", "summarize context");

            // D5: a referenced run profile selects the child model; otherwise the child inherits the parent's.
            assertThat(modelByObjective)
                    .containsEntry("find sources", childModel.modelId().value())
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
            assertThat(events.items())
                    .filteredOn(event -> event.payload() instanceof RunEventPayloads.ChildRunLifecycle)
                    .extracting(event -> ((RunEventPayloads.ChildRunLifecycle) event.payload()).childAgent())
                    .contains(researcherId, "summarizer");
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
        assertThatThrownBy(() -> builder(profile()).nonInteractiveChildTools().build())
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("CHILD_NON_INTERACTIVE_POLICY_REQUIRED");
    }

    @Test
    void nonInteractiveChildDeniesToolAndContinuesWhileParentStillRequiresApproval() throws Exception {
        Map<String, Integer> executions = new ConcurrentHashMap<>();
        AtomicBoolean childSawDenial = new AtomicBoolean();
        AgentChatModel model = request -> {
            boolean parent =
                    request.tools().stream().anyMatch(tool -> tool.name().equals("task"));
            var results = request.messages().stream()
                    .filter(message -> message.role() == ModelMessageRole.TOOL)
                    .toList();
            if (!parent) {
                if (results.isEmpty()) return SdkTestFixtures.toolCall("writer", Map.of("value", "child"));
                childSawDenial.set(
                        results.stream().anyMatch(message -> message.content().contains("denied by policy")));
                return answer("child continued after denial");
            }
            if (results.isEmpty()) return SdkTestFixtures.toolCall("writer", Map.of("value", "parent"));
            if (results.size() == 1)
                return SdkTestFixtures.toolCall("task", Map.of("agent", "writer-child", "objective", "Write"));
            return answer("parent completed");
        };
        var parentProfile = profile().withAllowedChildAgents(Set.of("writer-child"));
        try (HaifaAgent agent = builder(parentProfile, model)
                .policy(new PolicyPlatformContribution(
                        PolicyPresets.standardApproval(), new DefaultPolicyDecisionService()))
                .tool(new Writer(executions))
                .childAgent(ChildAgentSpec.of(
                        "writer-child", "Writes", "Attempt the write and handle denial.", Set.of("writer")))
                .nonInteractiveChildTools()
                .build()) {
            var started = agent.conversations().start(new StartConversationCommand("non-interactive", "Parent", "Go"));
            var interaction = SdkTestFixtures.awaitPending(agent, started.runId());
            assertThat(agent.runs().find(started.runId()).orElseThrow().status())
                    .isEqualTo(AgentRunStatus.WAITING_APPROVAL);
            SdkTestFixtures.approve(agent, started.runId(), interaction);
            var completed =
                    agent.runs().await(started.runId(), Duration.ofSeconds(20)).orElseThrow();
            assertThat(completed.status()).isEqualTo(AgentRunStatus.COMPLETED);
            var children = agent.runs().children(started.runId());
            assertThat(children).hasSize(1);
            var child = children.getFirst();
            assertThat(child.status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(agent.runs().pendingInteraction(child.runId())).isEmpty();
            assertThat(agent.runs()
                            .events(child.runId(), RunEventCursor.beforeFirst(child.runId()), 500)
                            .items())
                    .extracting(event -> event.eventType())
                    .doesNotContain("approval.requested", "interaction.requested");
            assertThat(agent.runs().toolCalls(child.runId()))
                    .singleElement()
                    .satisfies(call -> assertThat(call.status()).isEqualTo(ToolCallStatus.DENIED));
            assertThat(executions)
                    .containsOnlyKeys(started.runId().value())
                    .containsEntry(started.runId().value(), 1);
            assertThat(childSawDenial).isTrue();
        }
    }

    public record WriteRequest(String value) {}

    public record WriteResponse(String value) {}

    private record Writer(Map<String, Integer> executions) implements JavaTool<WriteRequest, WriteResponse> {
        @Override
        public JavaToolSpec<WriteRequest, WriteResponse> spec() {
            return JavaToolSpec.builder("writer", WriteRequest.class, WriteResponse.class)
                    .description("Writes a value")
                    .sideEffects(ToolSideEffect.FILE_WRITE)
                    .build();
        }

        @Override
        public WriteResponse invoke(WriteRequest request, JavaToolContext context) {
            executions.merge(context.runId().value(), 1, Integer::sum);
            return new WriteResponse(request.value());
        }
    }

    private static HaifaAgentBuilder builder(ProductProfile profile) {
        return builder(profile, request -> answer("unused"));
    }

    private static HaifaAgentBuilder builder(ProductProfile profile, AgentChatModel model) {
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
        return snapshot(modelId, 8_192);
    }

    private static ResolvedModelSnapshot snapshot(String modelId, int contextWindow) {
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
                contextWindow,
                1_024,
                Map.of(),
                Map.of());
    }
}

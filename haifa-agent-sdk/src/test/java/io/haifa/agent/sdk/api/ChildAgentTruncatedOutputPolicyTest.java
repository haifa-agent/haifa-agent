package io.haifa.agent.sdk.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.error.AgentErrorCode;
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
import io.haifa.agent.runtime.api.ChildRunCapacity;
import io.haifa.agent.runtime.api.ChildRunView;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.agent.runtime.api.TruncatedOutputPolicy;
import io.haifa.agent.sdk.contribution.InMemoryConversationContribution;
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.contribution.PolicyPlatformContribution;
import io.haifa.agent.sdk.contribution.SdkContributions;
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
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

public final class ChildAgentTruncatedOutputPolicyTest {
    private static final ResolvedModelSnapshot PARENT_MODEL = snapshot("parent-chat");
    private static final ResolvedModelSnapshot CHILD_MODEL = snapshot("child-chat");

    @Test
    void childOptInAcceptsPlainLengthAndParentCompletesWithItsResult() throws Exception {
        AtomicReference<Map<String, Object>> parentToolData = new AtomicReference<>();
        AgentChatModel model = request -> {
            boolean isParent =
                    request.tools().stream().map(ModelToolSpecification::name).anyMatch("task"::equals);
            if (isParent) {
                boolean hasToolResult = request.messages().stream().anyMatch(m -> m.role() == ModelMessageRole.TOOL);
                if (hasToolResult) {
                    parentToolData.set(request.messages().stream()
                            .filter(message -> message.role() == ModelMessageRole.TOOL)
                            .findFirst()
                            .orElseThrow()
                            .toolResultData());
                    return answer("Parent summary of child findings");
                }
                return new AgentChatResponse(
                        "r1",
                        "stub",
                        "",
                        List.of(new ModelToolCall(
                                new ProviderToolCallCorrelationId("task-1"),
                                "task",
                                Map.of("agent", "opt-in-child", "objective", "explain primes"))),
                        ModelFinishReason.TOOL_CALLS,
                        ModelUsage.unpriced(1, 1),
                        "",
                        Map.of());
            }
            // Child turn: terminates with finishReason LENGTH and non-empty plain text
            return new AgentChatResponse(
                    "r2",
                    "stub",
                    "The first twenty primes are 2, 3, 5, 7, 11, 13, 17, 19...",
                    List.of(),
                    ModelFinishReason.LENGTH,
                    ModelUsage.unpriced(50, 2048),
                    "",
                    Map.of());
        };

        ProductRunProfile childProfile = new ProductRunProfile(
                "child-opt-in-profile",
                "1.0.0",
                CHILD_MODEL.modelId().value(),
                AgentRunType.CHAT,
                budget(),
                new AgentRunLimits(8, 1, 1, 30_000, 30_000, 8, 8, 0),
                Map.of(),
                TruncatedOutputPolicy.ACCEPT_NONEMPTY_PLAIN_TEXT);

        ProductProfile productProfile = new ProductProfile(
                new ProductId("test-product"),
                new ProductVersion("1.0.0"),
                new AgentDefinitionId("parent"),
                new AgentDefinitionVersion(1, 0, 0),
                "test instructions",
                new ProductRunProfileRef("default", "1.0.0"),
                budget(),
                new AgentRunLimits(8, 1, 2, 30_000, 30_000, 8, 8, 4),
                Set.of(),
                Set.of(),
                Set.of("opt-in-child"));

        try (HaifaAgent agent = HaifaAgents.builder(productProfile)
                .model(new ModelContribution(
                        Map.of(ModelAdapterCoordinate.from(PARENT_MODEL), model),
                        PARENT_MODEL,
                        Map.of(
                                PARENT_MODEL.modelId().value(), PARENT_MODEL,
                                CHILD_MODEL.modelId().value(), CHILD_MODEL)))
                .persistence(SdkContributions.inMemoryPersistence())
                .conversation(new InMemoryConversationContribution())
                .childRunCapacity(new ChildRunCapacity(1))
                .maxConcurrentChildRuns(3)
                .executionExecutorFactoryForTests(Executors::newVirtualThreadPerTaskExecutor)
                .policy(new PolicyPlatformContribution(
                        PolicyPresets.standardApproval(), new DefaultPolicyDecisionService()))
                .runProfile(childProfile)
                .childAgent(new ChildAgentSpec(
                        "opt-in-child",
                        "Child agent accepting plain text length cap",
                        "Explain primes thoroughly.",
                        Optional.of(new ProductRunProfileRef("child-opt-in-profile", "1.0.0")),
                        Set.of()))
                .build()) {

            var started = agent.conversations().start(new StartConversationCommand("start", "Parent chat", "Go"));
            var parent =
                    agent.runs().await(started.runId(), Duration.ofSeconds(20)).orElseThrow();

            assertThat(parent.status()).isEqualTo(AgentRunStatus.COMPLETED);

            List<ChildRunView> children = agent.runs().children(parent.runId());
            assertThat(children).hasSize(1);
            ChildRunView child = children.get(0);
            assertThat(child.status()).isEqualTo(AgentRunStatus.COMPLETED);

            var childRun = agent.runs().find(child.runId()).orElseThrow();
            assertThat(childRun.status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(childRun.result().orElseThrow().warnings()).contains("TRUNCATED:LENGTH");
            assertThat(childRun.usage().inputTokens()).isEqualTo(50);
            assertThat(childRun.usage().outputTokens()).isEqualTo(2048);
            assertThat(parent.result().orElseThrow().warnings()).isEmpty();
            assertThat(parentToolData.get())
                    .containsEntry("modelOutputTruncated", true)
                    .containsEntry("modelFinishReason", "LENGTH")
                    .containsEntry("warnings", List.of("TRUNCATED:LENGTH"))
                    .containsEntry("truncated", false);
            var lifecycle =
                    agent.runs().events(child.runId(), RunEventCursor.beforeFirst(child.runId()), 100).items().stream()
                            .filter(event -> event.eventType().equals("model.call.succeeded"))
                            .map(event -> (RunEventPayloads.ModelLifecycle) event.payload())
                            .toList();
            assertThat(lifecycle).hasSize(1);
            assertThat(lifecycle.getFirst().finishReason()).isEqualTo("LENGTH");
            assertThat(lifecycle.getFirst().inputTokens()).isEqualTo(50);
            assertThat(lifecycle.getFirst().outputTokens()).isEqualTo(2048);
        }
    }

    @Test
    void parentDefaultPolicyRejectsPlainLengthAtRuntime() throws Exception {
        AgentChatModel model = request -> new AgentChatResponse(
                "parent-length",
                "stub",
                "Partial parent answer",
                List.of(),
                ModelFinishReason.LENGTH,
                ModelUsage.unpriced(50, 2048),
                "",
                Map.of());
        ProductProfile profile = new ProductProfile(
                new ProductId("strict-parent"),
                new ProductVersion("1.0.0"),
                new AgentDefinitionId("parent"),
                new AgentDefinitionVersion(1, 0, 0),
                "Answer the user",
                new ProductRunProfileRef("default", "1.0.0"),
                budget(),
                new AgentRunLimits(8, 1, 2, 30_000, 30_000, 8, 8, 4),
                Set.of(),
                Set.of());
        try (HaifaAgent agent = HaifaAgents.builder(profile)
                .model(new ModelContribution(
                        Map.of(ModelAdapterCoordinate.from(PARENT_MODEL), model),
                        PARENT_MODEL,
                        Map.of(PARENT_MODEL.modelId().value(), PARENT_MODEL)))
                .persistence(SdkContributions.inMemoryPersistence())
                .conversation(new InMemoryConversationContribution())
                .build()) {
            var started = agent.conversations().start(new StartConversationCommand("strict", "Parent", "Go"));
            var parent =
                    agent.runs().await(started.runId(), Duration.ofSeconds(20)).orElseThrow();
            assertThat(parent.status()).isEqualTo(AgentRunStatus.FAILED);
            assertThat(parent.error().orElseThrow().code()).isEqualTo(AgentErrorCode.MODEL_OUTPUT_TRUNCATED);
            assertThat(parent.result()).isEmpty();
        }
    }

    private enum ChildToolLengthTurn {
        LENGTH_WITH_TOOL_CALL,
        TOOL_CALLS_COMPLETES
    }

    @Test
    void childOptInPolicyFailsToolCallWithLengthInRealRuntimeBeforeToolExecution() throws Exception {
        runChildToolLengthScenario(ChildToolLengthTurn.LENGTH_WITH_TOOL_CALL);
    }

    @Test
    void childOptInPolicyAdmitsRegisteredToolAndExecutesOnceWhenFinishReasonIsToolCalls() throws Exception {
        runChildToolLengthScenario(ChildToolLengthTurn.TOOL_CALLS_COMPLETES);
    }

    /**
     * Both cases share the same registered no-side-effect JavaTool, the same Child run profile
     * ({@code ACCEPT_NONEMPTY_PLAIN_TEXT}) and the same Child admission; the only difference is the
     * Child's initial model response finishReason.
     */
    private void runChildToolLengthScenario(ChildToolLengthTurn scenario) throws Exception {
        AtomicInteger echoInvocations = new AtomicInteger();
        AtomicReference<Map<String, Object>> parentToolData = new AtomicReference<>();
        AgentChatModel model = request -> {
            boolean isParent =
                    request.tools().stream().map(ModelToolSpecification::name).anyMatch("task"::equals);
            if (isParent) {
                boolean hasToolResult = request.messages().stream().anyMatch(m -> m.role() == ModelMessageRole.TOOL);
                if (hasToolResult) {
                    parentToolData.set(request.messages().stream()
                            .filter(message -> message.role() == ModelMessageRole.TOOL)
                            .findFirst()
                            .orElseThrow()
                            .toolResultData());
                    return answer("Parent completed after receiving the child task result");
                }
                return new AgentChatResponse(
                        "r1",
                        "stub",
                        "",
                        List.of(new ModelToolCall(
                                new ProviderToolCallCorrelationId("task-1"),
                                "task",
                                Map.of("agent", "opt-in-child", "objective", "explain primes"))),
                        ModelFinishReason.TOOL_CALLS,
                        ModelUsage.unpriced(1, 1),
                        "",
                        Map.of());
            }
            if (request.messages().stream().anyMatch(m -> m.role() == ModelMessageRole.TOOL)) {
                return answer("Child echo result received");
            }
            assertThat(request.tools().stream().map(ModelToolSpecification::name))
                    .contains("echo");
            ModelToolCall echo =
                    new ModelToolCall(new ProviderToolCallCorrelationId("echo-1"), "echo", Map.of("value", "echo"));
            return new AgentChatResponse(
                    "r2",
                    "stub",
                    "The child started explaining primes and emitted a tool call mid response...",
                    List.of(echo),
                    scenario == ChildToolLengthTurn.LENGTH_WITH_TOOL_CALL
                            ? ModelFinishReason.LENGTH
                            : ModelFinishReason.TOOL_CALLS,
                    ModelUsage.unpriced(50, 2048),
                    "",
                    Map.of());
        };

        ProductRunProfile childProfile = new ProductRunProfile(
                "child-opt-in-profile",
                "1.0.0",
                CHILD_MODEL.modelId().value(),
                AgentRunType.CHAT,
                budget(),
                new AgentRunLimits(8, 1, 1, 30_000, 30_000, 8, 8, 0),
                Map.of(),
                TruncatedOutputPolicy.ACCEPT_NONEMPTY_PLAIN_TEXT);

        ProductProfile productProfile = new ProductProfile(
                new ProductId("test-product"),
                new ProductVersion("1.0.0"),
                new AgentDefinitionId("parent"),
                new AgentDefinitionVersion(1, 0, 0),
                "test instructions",
                new ProductRunProfileRef("default", "1.0.0"),
                budget(),
                new AgentRunLimits(8, 1, 2, 30_000, 30_000, 8, 8, 4),
                Set.of(),
                Set.of(),
                Set.of("opt-in-child"));

        try (HaifaAgent agent = HaifaAgents.builder(productProfile)
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
                .childRunCapacity(new ChildRunCapacity(1))
                .maxConcurrentChildRuns(3)
                .executionExecutorFactoryForTests(Executors::newVirtualThreadPerTaskExecutor)
                .childAgent(new ChildAgentSpec(
                        "opt-in-child",
                        "Child agent with opt-in truncated output policy",
                        "Explain primes thoroughly and echo the value.",
                        Optional.of(new ProductRunProfileRef("child-opt-in-profile", "1.0.0")),
                        Set.of("echo")))
                .tool(new EchoTool(echoInvocations))
                .build()) {

            var started = agent.conversations().start(new StartConversationCommand("child-tool-guard", "Parent", "Go"));
            var parent =
                    agent.runs().await(started.runId(), Duration.ofSeconds(20)).orElseThrow();

            List<ChildRunView> children = agent.runs().children(parent.runId());
            assertThat(children).hasSize(1);
            ChildRunView child = children.getFirst();
            var childRun = agent.runs().find(child.runId()).orElseThrow();
            assertThat(agent.runs().pendingInteraction(child.runId())).isEmpty();

            switch (scenario) {
                case LENGTH_WITH_TOOL_CALL -> {
                    assertThat(echoInvocations).hasValue(0);
                    assertThat(childRun.status()).isEqualTo(AgentRunStatus.FAILED);
                    assertThat(childRun.error().orElseThrow().code()).isEqualTo(AgentErrorCode.MODEL_OUTPUT_TRUNCATED);
                    assertThat(childRun.result()).isEmpty();
                    assertThat(parent.status()).isEqualTo(AgentRunStatus.COMPLETED);
                    assertThat(parent.result()).isPresent();
                    assertThat(parentToolData.get())
                            .containsEntry("status", "FAILED")
                            .containsEntry("reasonCode", "MODEL_OUTPUT_TRUNCATED");
                }
                case TOOL_CALLS_COMPLETES -> {
                    assertThat(echoInvocations).hasValue(1);
                    assertThat(childRun.status()).isEqualTo(AgentRunStatus.COMPLETED);
                    assertThat(childRun.result()).isPresent();
                    assertThat(childRun.result().orElseThrow().warnings()).doesNotContain("TRUNCATED:LENGTH");
                    assertThat(parent.status()).isEqualTo(AgentRunStatus.COMPLETED);
                    assertThat(parentToolData.get())
                            .containsEntry("status", "COMPLETED")
                            .doesNotContainKey("reasonCode");
                }
            }
        }
    }

    private record EchoTool(AtomicInteger invocations) implements JavaTool<EchoRequest, EchoResponse> {
        @Override
        public JavaToolSpec<EchoRequest, EchoResponse> spec() {
            return JavaToolSpec.builder("echo", EchoRequest.class, EchoResponse.class)
                    .title("Echo")
                    .description("Echoes the requested value back in-memory.")
                    .pure()
                    .build();
        }

        @Override
        public EchoResponse invoke(EchoRequest request, JavaToolContext context) {
            invocations.incrementAndGet();
            return new EchoResponse(request.value());
        }
    }

    public record EchoRequest(String value) {}

    public record EchoResponse(String value) {}

    private static AgentRunBudget budget() {
        return new AgentRunBudget(100_000, 100_000, 100_000, 8, 8, 4, "USD", 1_000);
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
                2_048,
                Map.of(),
                Map.of());
    }
}

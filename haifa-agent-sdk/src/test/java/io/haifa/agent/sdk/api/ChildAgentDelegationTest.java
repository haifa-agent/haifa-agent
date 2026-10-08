package io.haifa.agent.sdk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.error.AgentErrorCode;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunId;
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
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.ChildRunCapacity;
import io.haifa.agent.runtime.api.ChildRunView;
import io.haifa.agent.runtime.api.RunCancellation;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.agent.runtime.api.RunMessageCursor;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
                .childRunCapacity(new ChildRunCapacity(1))
                .maxConcurrentChildRuns(3)
                .executionExecutorFactoryForTests(Executors::newVirtualThreadPerTaskExecutor)
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

    // Oracle16: a Child that is already created but whose executor task is held before it physically
    // starts must still settle when its Parent stops, without inventing a physical start, model call or step.
    @Test
    void parentCancelWhileChildIsQueuedSettlesTheChildWithoutPhysicalExecution() throws Exception {
        assertQueuedChildSettlesWhileExecutorIsHeld(
                "cancel-held-child",
                AgentRunStatus.CANCELLED,
                (agent, runId) -> agent.runs().handle(runId).cancel());
    }

    @Test
    void parentDeadlineTimeoutWhileChildIsQueuedSettlesTheChildWithoutPhysicalExecution() throws Exception {
        assertQueuedChildSettlesWhileExecutorIsHeld(
                "timeout-held-child", AgentRunStatus.TIMEOUT, (agent, runId) -> agent.runs()
                        .handle(runId)
                        .cancel(RunCancellation.deadlineExceeded(Duration.ofSeconds(1))));
    }

    @Test
    void executionExecutorFactoryFailsClosedForNullFactoryAndNullExecutor() {
        assertThatThrownBy(() -> builder(profile()).executionExecutorFactoryForTests(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder(profile())
                        .executionExecutorFactoryForTests(() -> null)
                        .build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void executionExecutorFactoryCreatesAndClosesAnOwnedExecutorForEachAgent() throws Exception {
        List<ExecutorService> created = new ArrayList<>();
        java.util.function.Supplier<ExecutorService> factory = () -> {
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            created.add(executor);
            return executor;
        };
        try (HaifaAgent first = builder(profile(), request -> answer("factory-first"))
                        .executionExecutorFactoryForTests(factory)
                        .build();
                HaifaAgent second = builder(profile(), request -> answer("factory-second"))
                        .executionExecutorFactoryForTests(factory)
                        .build()) {
            assertThat(created).hasSize(2);
            assertThat(created.getFirst()).isNotSameAs(created.getLast());
            for (HaifaAgent agent : List.of(first, second)) {
                AgentRunId runId = agent.conversations()
                        .start(new StartConversationCommand("factory", "Factory", "Answer once"))
                        .runId();
                assertThat(agent.runs()
                                .await(runId, Duration.ofSeconds(10))
                                .orElseThrow()
                                .status())
                        .isEqualTo(AgentRunStatus.COMPLETED);
                assertThat(runLifecycleStatuses(agent, runId)).contains("RUNNING");
            }
        }
        assertThat(created).allSatisfy(executor -> {
            assertThat(executor.isShutdown()).isTrue();
            assertThat(executor.isTerminated()).isTrue();
        });
    }

    private void assertQueuedChildSettlesWhileExecutorIsHeld(
            String objective, AgentRunStatus expectedParentStatus, ParentStopper stopper) throws Exception {
        GatedExecutionExecutor gated = new GatedExecutionExecutor();
        ChildRunCapacity capacity = new ChildRunCapacity(1);
        AtomicInteger childModelCalls = new AtomicInteger();
        AgentChatModel model = request -> {
            boolean parent =
                    request.tools().stream().map(ModelToolSpecification::name).anyMatch("task"::equals);
            if (!parent) {
                childModelCalls.incrementAndGet();
                return answer("a queued child must never reach the model");
            }
            boolean toolResults =
                    request.messages().stream().anyMatch(message -> message.role() == ModelMessageRole.TOOL);
            return toolResults
                    ? answer("parent observed the settled child")
                    : new AgentChatResponse(
                            "r",
                            "stub",
                            "",
                            List.of(call("a", "worker", objective)),
                            ModelFinishReason.TOOL_CALLS,
                            ModelUsage.unpriced(1, 1),
                            "",
                            Map.of());
        };

        HaifaAgent agent = builder(profile().withAllowedChildAgents(Set.of("worker")), model)
                .policy(new PolicyPlatformContribution(
                        PolicyPresets.standardApproval(), new DefaultPolicyDecisionService()))
                .childAgent(ChildAgentSpec.of("worker", "Works", "Do the work.", Set.of()))
                .childRunCapacity(capacity)
                .executionExecutorFactoryForTests(() -> gated)
                .build();
        try {
            AgentRunId parentRunId = agent.conversations()
                    .start(new StartConversationCommand("held-" + objective, "Parent", "Delegate once"))
                    .runId();
            assertThat(gated.awaitChildRetained(10, TimeUnit.SECONDS))
                    .as("the created Child's execution task reached the injected executor")
                    .isTrue();

            // The public child projection shows exactly the created but not physically started Child.
            List<ChildRunView> children = agent.runs().children(parentRunId);
            assertThat(children).hasSize(1);
            ChildRunView queued = children.getFirst();
            assertThat(queued.objective()).isEqualTo(objective);
            assertThat(queued.status()).isEqualTo(AgentRunStatus.QUEUED);
            assertThat(queued.parentRunId()).isEqualTo(parentRunId);
            assertThat(queued.startedAt()).isEmpty();
            assertThat(runLifecycleStatuses(agent, queued.runId())).doesNotContain("RUNNING");
            assertThat(capacity.tryAcquire())
                    .as("the created Child owns its capacity slot")
                    .isFalse();
            assertThat(eventTypes(agent, queued.runId()))
                    .doesNotContain("run.started")
                    .noneMatch(type -> type.startsWith("model.call"))
                    .noneMatch(type -> type.startsWith("step."));
            assertThat(eventTypes(agent, parentRunId))
                    .doesNotContain(
                            "child.run.completed", "child.run.failed", "child.run.cancelled", "child.run.timed-out");

            stopper.stop(agent, parentRunId);
            var parent = agent.runs().await(parentRunId, Duration.ofSeconds(20)).orElseThrow();
            assertThat(parent.status()).isEqualTo(expectedParentStatus);

            assertThat(agent.runs().find(queued.runId()).orElseThrow().status())
                    .as("an already created Child must settle when its Parent stops")
                    .isEqualTo(AgentRunStatus.CANCELLED);
            assertThat(agent.runs()
                            .find(queued.runId())
                            .orElseThrow()
                            .terminationReason()
                            .orElseThrow()
                            .code())
                    .isEqualTo("PARENT_CANCELLED");
            assertThat(capacity.tryAcquire())
                    .as("terminal Child retains its slot until the wrapper drains")
                    .isFalse();

            var taskCalls = agent.runs().toolCalls(parentRunId);
            assertThat(taskCalls).singleElement().satisfies(task -> {
                assertThat(task.toolName()).isEqualTo("task");
                assertThat(task.status()).isIn(ToolCallStatus.FAILED, ToolCallStatus.CANCELLED, ToolCallStatus.TIMEOUT);
            });
            var task = taskCalls.getFirst();
            var parentMessages = agent.runs().messages(parentRunId, RunMessageCursor.beforeFirst(parentRunId), 100);
            assertThat(parentMessages.items())
                    .filteredOn(message -> "TOOL".equals(message.role()))
                    .singleElement()
                    .satisfies(message -> {
                        assertThat(message.toolCalls())
                                .extracting(call -> call.id())
                                .containsExactly(task.id());
                        assertThat(message.text()).containsAnyOf("cancelled", "stopped");
                    });

            // Cleanup only: drain the held wrapper before asserting no physical execution was invented.
            gated.releaseRetained();
            agent.runs().await(queued.runId(), Duration.ofSeconds(20));
            assertThat(capacity.tryAcquire())
                    .as("drained terminal Child releases its slot")
                    .isTrue();
            capacity.release();

            assertThat(childModelCalls.get())
                    .as("no model call may be invented")
                    .isZero();
            assertThat(agent.runs().find(queued.runId()).orElseThrow().usage().modelCalls())
                    .as("no model call may be invented")
                    .isZero();
            assertThat(agent.runs().toolCalls(queued.runId())).isEmpty();
            assertThat(eventTypes(agent, queued.runId()))
                    .doesNotContain("run.started")
                    .noneMatch(type -> type.startsWith("model.call"))
                    .noneMatch(type -> type.startsWith("step."));
            ChildRunView settled = agent.runs().children(parentRunId).getFirst();
            assertThat(settled.status()).isEqualTo(AgentRunStatus.CANCELLED);
            assertThat(runLifecycleStatuses(agent, queued.runId())).doesNotContain("RUNNING");
            assertThat(settled.startedAt())
                    .as("physical start must not be invented")
                    .isEmpty();
        } finally {
            gated.releaseRetained();
            agent.close();
        }
    }

    private static List<String> eventTypes(HaifaAgent agent, AgentRunId runId) {
        return agent.runs().events(runId, RunEventCursor.beforeFirst(runId), 500).items().stream()
                .map(AgentRunEvent::eventType)
                .toList();
    }

    @FunctionalInterface
    private interface ParentStopper {
        void stop(HaifaAgent agent, AgentRunId runId);
    }

    private static List<String> runLifecycleStatuses(HaifaAgent agent, AgentRunId runId) {
        return agent.runs().events(runId, RunEventCursor.beforeFirst(runId), 500).items().stream()
                .map(AgentRunEvent::payload)
                .filter(RunEventPayloads.RunLifecycle.class::isInstance)
                .map(RunEventPayloads.RunLifecycle.class::cast)
                .map(RunEventPayloads.RunLifecycle::status)
                .toList();
    }

    /**
     * Deterministic executor injection: the first submitted task (the Parent) delegates to a real
     * executor; every later submission (the created Child) is retained before execution so the test
     * can cancel or time out the Parent while the Child can never have physically started.
     */
    private static final class GatedExecutionExecutor extends AbstractExecutorService {
        private final ExecutorService delegate = Executors.newCachedThreadPool();
        private final List<Runnable> retained = new CopyOnWriteArrayList<>();
        private final CountDownLatch childRetained = new CountDownLatch(1);
        private final AtomicInteger submissions = new AtomicInteger();

        @Override
        public void execute(Runnable command) {
            if (submissions.getAndIncrement() == 0) {
                delegate.execute(command);
                return;
            }
            retained.add(command);
            childRetained.countDown();
        }

        boolean awaitChildRetained(long timeout, TimeUnit unit) throws InterruptedException {
            return childRetained.await(timeout, unit);
        }

        void releaseRetained() {
            List<Runnable> pending = List.copyOf(retained);
            retained.removeAll(pending);
            for (Runnable task : pending) {
                try {
                    delegate.submit(task).get(10, TimeUnit.SECONDS);
                } catch (Exception error) {
                    throw new IllegalStateException(
                            "held Child wrapper did not drain within its cleanup budget", error);
                }
            }
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
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

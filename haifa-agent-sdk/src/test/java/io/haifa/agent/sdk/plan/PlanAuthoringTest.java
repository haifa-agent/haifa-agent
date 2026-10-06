package io.haifa.agent.sdk.plan;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.runtime.api.AgentPlanView;
import io.haifa.agent.runtime.api.TodoItemView;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.sdk.SdkTestFixtures;
import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.sdk.api.HaifaAgents;
import io.haifa.agent.sdk.contribution.InMemoryConversationContribution;
import io.haifa.agent.sdk.conversation.ConversationRecord;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.conversation.SubmitConversationTurnCommand;
import io.haifa.agent.sdk.internal.InMemoryPersistenceContribution;
import io.haifa.agent.sdk.product.ChildAgentSpec;
import io.haifa.agent.sdk.product.ProductProfile;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

public class PlanAuthoringTest {

    @Test
    void disabledByDefault_toolAndPromptAreNotPresent() throws Exception {
        try (HaifaAgent agent = SdkTestFixtures.builder("p-disabled").build()) {
            assertThat(agent.profile().allowedTools()).doesNotContain("write_todos");

            var started = agent.conversations()
                    .start(new StartConversationCommand("start-1", "Disabled test", "Hello"));
            AgentRunId runId = started.runId();
            agent.runs().await(runId);

            assertThat(agent.runs().plan(runId)).isEmpty();
            var promptDiagnostics = agent.runs().promptDiagnostics(runId);
            assertThat(promptDiagnostics.components())
                    .noneMatch(component -> component.componentId().equals("runtime-todo"));
        }
    }

    @Test
    void enabled_writesAllThreeStatusesAndReadsBackViaPlan() throws Exception {
        AtomicReference<String> systemPromptSeen = new AtomicReference<>();
        AgentChatModel queue = SdkTestFixtures.queueModel(
                SdkTestFixtures.toolCall("write_todos", Map.of("todos", List.of(
                        Map.of("content", "Step 1", "status", "completed"),
                        Map.of("content", "Step 2", "status", "in_progress"),
                        Map.of("content", "Step 3", "status", "pending")))),
                SdkTestFixtures.finalAnswer("Plan established."));

        AgentChatModel model = request -> {
            request.messages().stream()
                    .filter(m -> m.role() == ModelMessageRole.SYSTEM && m.content().contains("Track multi-step objectives"))
                    .findFirst()
                    .ifPresent(m -> systemPromptSeen.set(m.content()));
            return queue.invoke(request);
        };

        try (HaifaAgent agent = SdkTestFixtures.builder("p-enabled")
                .model(SdkTestFixtures.modelContribution(model))
                .enablePlanAuthoring()
                .build()) {

            var started = agent.conversations()
                    .start(new StartConversationCommand("start-2", "Plan test", "Create plan"));
            AgentRunId runId = started.runId();
            agent.runs().await(runId);

            Optional<AgentPlanView> planOpt = agent.runs().plan(runId);
            assertThat(planOpt).isPresent();
            AgentPlanView plan = planOpt.get();
            assertThat(plan.revision()).isEqualTo(1L);
            assertThat(plan.objective()).isEqualTo("Execution plan");
            assertThat(plan.items()).hasSize(3);

            TodoItemView item0 = plan.items().get(0);
            assertThat(item0.title()).isEqualTo("Step 1");
            assertThat(item0.status()).isEqualTo("COMPLETED");
            assertThat(item0.startedAt()).isPresent();
            assertThat(item0.completedAt()).isPresent();

            TodoItemView item1 = plan.items().get(1);
            assertThat(item1.title()).isEqualTo("Step 2");
            assertThat(item1.status()).isEqualTo("IN_PROGRESS");
            assertThat(item1.startedAt()).isPresent();
            assertThat(item1.completedAt()).isEmpty();

            TodoItemView item2 = plan.items().get(2);
            assertThat(item2.title()).isEqualTo("Step 3");
            assertThat(item2.status()).isEqualTo("PENDING");
            assertThat(item2.startedAt()).isEmpty();
            assertThat(item2.completedAt()).isEmpty();

            assertThat(systemPromptSeen.get()).contains(PlanAuthoringSpec.DEFAULT_SYSTEM_PROMPT);

            var promptDiagnostics = agent.runs().promptDiagnostics(runId);
            assertThat(promptDiagnostics.components())
                    .anyMatch(c -> c.componentId().equals("runtime-todo"));
        }
    }

    @Test
    void secondWrite_replacesWholeListIncrementsRevisionAndAllowsCompletedBackToPending() throws Exception {
        AgentChatModel model = SdkTestFixtures.queueModel(
                SdkTestFixtures.toolCall("write_todos", Map.of("todos", List.of(
                        Map.of("content", "Task A", "status", "completed"),
                        Map.of("content", "Task B", "status", "in_progress")))),
                SdkTestFixtures.toolCall("write_todos", Map.of("todos", List.of(
                        Map.of("content", "Task A", "status", "pending"),
                        Map.of("content", "Task C", "status", "in_progress")))),
                SdkTestFixtures.finalAnswer("Run complete"));

        try (HaifaAgent agent = SdkTestFixtures.builder("p-replace")
                .model(SdkTestFixtures.modelContribution(model))
                .enablePlanAuthoring()
                .build()) {

            var conv = agent.conversations()
                    .start(new StartConversationCommand("start-3", "Replace test", "Run"));
            agent.runs().await(conv.runId());

            Optional<AgentPlanView> planOpt = agent.runs().plan(conv.runId());
            assertThat(planOpt).isPresent();
            AgentPlanView plan = planOpt.get();
            assertThat(plan.revision()).isEqualTo(2L);
            assertThat(plan.items()).hasSize(2);

            assertThat(plan.items().get(0).title()).isEqualTo("Task A");
            assertThat(plan.items().get(0).status()).isEqualTo("PENDING");

            assertThat(plan.items().get(1).title()).isEqualTo("Task C");
            assertThat(plan.items().get(1).status()).isEqualTo("IN_PROGRESS");
        }
    }

    @Test
    void invalidStatusBlankContentOrMissingTodos_returnsErrorPlanUnchangedAndRunSucceeds() throws Exception {
        AgentChatModel model = SdkTestFixtures.queueModel(
                SdkTestFixtures.toolCall("write_todos", Map.of("todos", List.of(
                        Map.of("content", "Valid task", "status", "pending")))),
                SdkTestFixtures.toolCall("write_todos", Map.of("todos", List.of(
                        Map.of("content", "Broken task", "status", "doing")))),
                SdkTestFixtures.toolCall("write_todos", Map.of("todos", List.of(
                        Map.of("content", "   ", "status", "pending")))),
                SdkTestFixtures.toolCall("write_todos", Map.of()),
                SdkTestFixtures.finalAnswer("All handled"));

        try (HaifaAgent agent = SdkTestFixtures.builder("p-validation")
                .model(SdkTestFixtures.modelContribution(model))
                .enablePlanAuthoring()
                .build()) {

            var conv = agent.conversations()
                    .start(new StartConversationCommand("start-4", "Val test", "Init"));
            var snap = agent.runs().await(conv.runId());
            assertThat(snap.status()).isEqualTo(AgentRunStatus.COMPLETED);

            AgentPlanView plan = agent.runs().plan(conv.runId()).orElseThrow();
            assertThat(plan.revision()).isEqualTo(1L);
            assertThat(plan.items()).hasSize(1);
            assertThat(plan.items().get(0).title()).isEqualTo("Valid task");
        }
    }

    @Test
    void emptyList_clearsPlanItems() throws Exception {
        AgentChatModel model = SdkTestFixtures.queueModel(
                SdkTestFixtures.toolCall("write_todos", Map.of("todos", List.of(
                        Map.of("content", "Initial task", "status", "pending")))),
                SdkTestFixtures.toolCall("write_todos", Map.of("todos", List.of())),
                SdkTestFixtures.finalAnswer("Plan cleared"));

        try (HaifaAgent agent = SdkTestFixtures.builder("p-clear")
                .model(SdkTestFixtures.modelContribution(model))
                .enablePlanAuthoring()
                .build()) {

            var conv = agent.conversations()
                    .start(new StartConversationCommand("start-5", "Clear test", "Set"));
            agent.runs().await(conv.runId());

            AgentPlanView plan = agent.runs().plan(conv.runId()).orElseThrow();
            assertThat(plan.revision()).isEqualTo(2L);
            assertThat(plan.items()).isEmpty();
        }
    }

    @Test
    void policyEvaluation_requiresNoApproval() throws Exception {
        AgentChatModel model = SdkTestFixtures.queueModel(
                SdkTestFixtures.toolCall("write_todos", Map.of("todos", List.of(
                        Map.of("content", "Automated plan", "status", "in_progress")))),
                SdkTestFixtures.finalAnswer("Done without challenge"));

        try (HaifaAgent agent = SdkTestFixtures.builder("p-policy")
                .model(SdkTestFixtures.modelContribution(model))
                .enablePlanAuthoring()
                .build()) {

            var started = agent.conversations()
                    .start(new StartConversationCommand("start-6", "Policy test", "Execute"));
            AgentRunId runId = started.runId();

            assertThat(agent.runs().pendingInteraction(runId)).isEmpty();

            var snapshot = agent.runs().await(runId);
            assertThat(snapshot.status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(agent.runs().plan(runId)).isPresent();
        }
    }

    @Test
    void childAgent_doesNotInheritPlanTool() throws Exception {
        AtomicReference<List<String>> parentTools = new AtomicReference<>();
        AtomicReference<List<String>> childTools = new AtomicReference<>();

        AgentChatModel model = request -> {
            boolean isParent = request.tools().stream().map(ModelToolSpecification::name).anyMatch("task"::equals);
            if (isParent) {
                parentTools.set(request.tools().stream().map(ModelToolSpecification::name).toList());
                boolean hasToolResult = request.messages().stream().anyMatch(m -> m.role() == ModelMessageRole.TOOL);
                if (hasToolResult) {
                    return SdkTestFixtures.finalAnswer("all finished");
                }
                return SdkTestFixtures.toolCall("task", Map.of("agent", "subagent-worker", "objective", "subtask work"));
            }
            childTools.set(request.tools().stream().map(ModelToolSpecification::name).toList());
            return SdkTestFixtures.finalAnswer("child finished");
        };

        ProductProfile profile = new ProductProfile(
                new io.haifa.agent.sdk.product.ProductId("p-child"),
                new io.haifa.agent.sdk.product.ProductVersion("1.0.0"),
                new io.haifa.agent.core.agent.AgentDefinitionId("p-child-agent"),
                new io.haifa.agent.core.agent.AgentDefinitionVersion(1, 0, 0),
                "Parent instructions",
                new io.haifa.agent.sdk.product.ProductRunProfileRef("p-child-chat", "1.0.0"),
                new io.haifa.agent.core.run.AgentRunBudget(100_000, 100_000, 100_000, 8, 8, 4, "USD", 1_000),
                new io.haifa.agent.core.run.AgentRunLimits(8, 1, 2, 30_000, 30_000, 8, 8, 4),
                Set.of(),
                Set.of(),
                Set.of("subagent-worker"));

        try (HaifaAgent agent = HaifaAgents.builder(profile)
                .model(SdkTestFixtures.modelContribution(model))
                .persistence(SdkTestFixtures.persistenceContribution())
                .conversation(new InMemoryConversationContribution())
                .policy(new io.haifa.agent.sdk.contribution.PolicyPlatformContribution(
                        io.haifa.agent.policy.api.PolicyPresets.standardApproval(),
                        new io.haifa.agent.policy.core.DefaultPolicyDecisionService()))
                .childAgent(ChildAgentSpec.of("subagent-worker", "Worker description", "Worker instructions", Set.of()))
                .enablePlanAuthoring()
                .build()) {

            var started = agent.conversations()
                    .start(new StartConversationCommand("start-7", "Child test", "Start parent"));
            agent.runs().await(started.runId());

            assertThat(parentTools.get()).contains("write_todos");
            assertThat(childTools.get()).isNotNull();
            assertThat(childTools.get()).doesNotContain("write_todos");
        }
    }

    @Test
    void inMemoryStoreReopen_preservesPlanAcrossInstances() throws Exception {
        var sharedStore = new InMemoryRuntimeStore();
        RuntimePersistencePorts ports = RuntimePersistencePorts.inMemory(sharedStore);

        AgentChatModel model = SdkTestFixtures.queueModel(
                SdkTestFixtures.toolCall("write_todos", Map.of("todos", List.of(
                        Map.of("content", "Persistent step 1", "status", "completed"),
                        Map.of("content", "Persistent step 2", "status", "in_progress")))),
                SdkTestFixtures.finalAnswer("Initial persistence complete"));

        AgentRunId runId;
        try (HaifaAgent agent1 = HaifaAgents.builder(SdkTestFixtures.profile("p-persist"))
                .model(SdkTestFixtures.modelContribution(model))
                .persistence(new InMemoryPersistenceContribution(ports))
                .conversation(new InMemoryConversationContribution())
                .policy(new io.haifa.agent.sdk.contribution.PolicyPlatformContribution(
                        io.haifa.agent.policy.api.PolicyPresets.standardApproval(),
                        new io.haifa.agent.policy.core.DefaultPolicyDecisionService()))
                .enablePlanAuthoring()
                .build()) {

            var started = agent1.conversations()
                    .start(new StartConversationCommand("start-8", "Persist test", "Run"));
            runId = started.runId();
            agent1.runs().await(runId);
            assertThat(agent1.runs().plan(runId)).isPresent();
        }

        // Reopen new HaifaAgent instance with the same shared store
        try (HaifaAgent agent2 = HaifaAgents.builder(SdkTestFixtures.profile("p-persist"))
                .model(SdkTestFixtures.modelContribution())
                .persistence(new InMemoryPersistenceContribution(ports))
                .conversation(new InMemoryConversationContribution())
                .policy(new io.haifa.agent.sdk.contribution.PolicyPlatformContribution(
                        io.haifa.agent.policy.api.PolicyPresets.standardApproval(),
                        new io.haifa.agent.policy.core.DefaultPolicyDecisionService()))
                .enablePlanAuthoring()
                .build()) {

            Optional<AgentPlanView> reopenedPlan = agent2.runs().plan(runId);
            assertThat(reopenedPlan).isPresent();
            AgentPlanView plan = reopenedPlan.get();
            assertThat(plan.revision()).isEqualTo(1L);
            assertThat(plan.items()).hasSize(2);
            assertThat(plan.items().get(0).title()).isEqualTo("Persistent step 1");
            assertThat(plan.items().get(0).status()).isEqualTo("COMPLETED");
            assertThat(plan.items().get(1).title()).isEqualTo("Persistent step 2");
            assertThat(plan.items().get(1).status()).isEqualTo("IN_PROGRESS");
        }
    }
}

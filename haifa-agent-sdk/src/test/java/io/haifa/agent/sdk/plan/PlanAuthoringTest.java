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
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.conversation.SubmitConversationTurnCommand;
import io.haifa.agent.sdk.internal.InMemoryPersistenceContribution;
import io.haifa.agent.sdk.product.ChildAgentSpec;
import io.haifa.agent.sdk.product.ProductProfile;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolRisk;
import java.time.Duration;
import java.util.ArrayList;
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
            agent.runs().await(runId, Duration.ofSeconds(10));

            assertThat(agent.runs().plan(runId)).isEmpty();
            var promptDiagnostics = agent.runs().promptDiagnostics(runId);
            assertThat(promptDiagnostics.components())
                    .noneMatch(component -> component.id().value().equals("runtime-todo"));
        }
    }

    @Test
    void enabled_writesAllThreeStatusesAndReadsBackViaPlan() throws Exception {
        AgentChatModel model = SdkTestFixtures.queueModel(
                SdkTestFixtures.toolCall("write_todos", Map.of("todos", List.of(
                        Map.of("content", "Step 1", "status", "completed"),
                        Map.of("content", "Step 2", "status", "in_progress"),
                        Map.of("content", "Step 3", "status", "pending")))),
                SdkTestFixtures.finalAnswer("Plan established."));

        try (HaifaAgent agent = SdkTestFixtures.builder("p-enabled")
                .model(SdkTestFixtures.modelContribution(model))
                .enablePlanAuthoring()
                .build()) {

            var started = agent.conversations()
                    .start(new StartConversationCommand("start-2", "Plan test", "Create plan"));
            AgentRunId runId = started.runId();
            agent.runs().await(runId, Duration.ofSeconds(10));

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

            var promptDiagnostics = agent.runs().promptDiagnostics(runId);
            assertThat(promptDiagnostics.components())
                    .anyMatch(c -> c.id().value().equals("runtime-todo")
                            && c.text().contains(PlanAuthoringSpec.DEFAULT_SYSTEM_PROMPT));
        }
    }

    @Test
    void secondWrite_replacesWholeListIncrementsRevisionAndAllowsCompletedBackToPending() throws Exception {
        AgentChatModel model = SdkTestFixtures.queueModel(
                SdkTestFixtures.toolCall("write_todos", Map.of("todos", List.of(
                        Map.of("content", "Task A", "status", "completed"),
                        Map.of("content", "Task B", "status", "in_progress")))),
                SdkTestFixtures.finalAnswer("Turn 1 complete"),
                SdkTestFixtures.toolCall("write_todos", Map.of("todos", List.of(
                        Map.of("content", "Task A", "status", "pending"),
                        Map.of("content", "Task C", "status", "in_progress")))),
                SdkTestFixtures.finalAnswer("Turn 2 complete"));

        try (HaifaAgent agent = SdkTestFixtures.builder("p-replace")
                .model(SdkTestFixtures.modelContribution(model))
                .enablePlanAuthoring()
                .build()) {

            var conv = agent.conversations()
                    .start(new StartConversationCommand("start-3", "Replace test", "First turn"));
            agent.runs().await(conv.runId(), Duration.ofSeconds(10));

            var turn2 = agent.conversations()
                    .submitTurn(new SubmitConversationTurnCommand(conv.conversationId(), "start-3-t2", "Second turn"));
            agent.runs().await(turn2.runId(), Duration.ofSeconds(10));

            Optional<AgentPlanView> planOpt = agent.runs().plan(turn2.runId());
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
                SdkTestFixtures.finalAnswer("Initial plan established"),
                SdkTestFixtures.toolCall("write_todos", Map.of("todos", List.of(
                        Map.of("content", "Broken task", "status", "doing")))),
                SdkTestFixtures.finalAnswer("Handled invalid status"),
                SdkTestFixtures.toolCall("write_todos", Map.of("todos", List.of(
                        Map.of("content", "   ", "status", "pending")))),
                SdkTestFixtures.finalAnswer("Handled blank content"),
                SdkTestFixtures.toolCall("write_todos", Map.of()),
                SdkTestFixtures.finalAnswer("Handled missing todos"));

        try (HaifaAgent agent = SdkTestFixtures.builder("p-validation")
                .model(SdkTestFixtures.modelContribution(model))
                .enablePlanAuthoring()
                .build()) {

            var conv = agent.conversations()
                    .start(new StartConversationCommand("start-4", "Val test", "Init"));
            agent.runs().await(conv.runId(), Duration.ofSeconds(10));

            // Turn 2: invalid status
            var t2 = agent.conversations()
                    .submitTurn(new SubmitConversationTurnCommand(conv.conversationId(), "start-4-t2", "Turn 2"));
            var snap2 = agent.runs().await(t2.runId(), Duration.ofSeconds(10));
            assertThat(snap2.status()).isEqualTo(AgentRunStatus.COMPLETED);

            AgentPlanView planAfterT2 = agent.runs().plan(t2.runId()).orElseThrow();
            assertThat(planAfterT2.revision()).isEqualTo(1L);
            assertThat(planAfterT2.items()).hasSize(1);
            assertThat(planAfterT2.items().get(0).title()).isEqualTo("Valid task");

            // Turn 3: blank content
            var t3 = agent.conversations()
                    .submitTurn(new SubmitConversationTurnCommand(conv.conversationId(), "start-4-t3", "Turn 3"));
            var snap3 = agent.runs().await(t3.runId(), Duration.ofSeconds(10));
            assertThat(snap3.status()).isEqualTo(AgentRunStatus.COMPLETED);

            AgentPlanView planAfterT3 = agent.runs().plan(t3.runId()).orElseThrow();
            assertThat(planAfterT3.revision()).isEqualTo(1L);

            // Turn 4: missing todos
            var t4 = agent.conversations()
                    .submitTurn(new SubmitConversationTurnCommand(conv.conversationId(), "start-4-t4", "Turn 4"));
            var snap4 = agent.runs().await(t4.runId(), Duration.ofSeconds(10));
            assertThat(snap4.status()).isEqualTo(AgentRunStatus.COMPLETED);

            AgentPlanView planAfterT4 = agent.runs().plan(t4.runId()).orElseThrow();
            assertThat(planAfterT4.revision()).isEqualTo(1L);
        }
    }

    @Test
    void emptyList_clearsPlanItems() throws Exception {
        AgentChatModel model = SdkTestFixtures.queueModel(
                SdkTestFixtures.toolCall("write_todos", Map.of("todos", List.of(
                        Map.of("content", "Initial task", "status", "pending")))),
                SdkTestFixtures.finalAnswer("Plan set"),
                SdkTestFixtures.toolCall("write_todos", Map.of("todos", List.of())),
                SdkTestFixtures.finalAnswer("Plan cleared"));

        try (HaifaAgent agent = SdkTestFixtures.builder("p-clear")
                .model(SdkTestFixtures.modelContribution(model))
                .enablePlanAuthoring()
                .build()) {

            var conv = agent.conversations()
                    .start(new StartConversationCommand("start-5", "Clear test", "Set"));
            agent.runs().await(conv.runId(), Duration.ofSeconds(10));

            var t2 = agent.conversations()
                    .submitTurn(new SubmitConversationTurnCommand(conv.conversationId(), "start-5-t2", "Clear"));
            agent.runs().await(t2.runId(), Duration.ofSeconds(10));

            AgentPlanView plan = agent.runs().plan(t2.runId()).orElseThrow();
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

            var snapshot = agent.runs().await(runId, Duration.ofSeconds(10));
            assertThat(snapshot.status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(agent.runs().plan(runId)).isPresent();
        }
    }

    @Test
    void childAgent_doesNotInheritPlanTool() throws Exception {
        AtomicReference<List<String>> parentTools = new AtomicReference<>();
        AtomicReference<List<String>> childTools = new AtomicReference<>();

        AgentChatModel model = request -> {
            boolean isChild = request.messages().stream()
                    .anyMatch(m -> m.content().contains("subtask"));
            if (isChild) {
                childTools.set(request.tools().stream().map(ModelToolSpecification::name).toList());
                return SdkTestFixtures.finalAnswer("child finished");
            }
            parentTools.set(request.tools().stream().map(ModelToolSpecification::name).toList());
            boolean hasToolResult = request.messages().stream().anyMatch(m -> m.role() == ModelMessageRole.TOOL);
            if (hasToolResult) {
                return SdkTestFixtures.finalAnswer("all finished");
            }
            return SdkTestFixtures.toolCall("task", Map.of("subagent", "subagent-worker", "description", "subtask work"));
        };

        ProductProfile profile = SdkTestFixtures.profile("p-child")
                .withAllowedChildAgents(Set.of("subagent-worker"));

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
            agent.runs().await(started.runId(), Duration.ofSeconds(15));

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
                .enablePlanAuthoring()
                .build()) {

            var started = agent1.conversations()
                    .start(new StartConversationCommand("start-8", "Persist test", "Run"));
            runId = started.runId();
            agent1.runs().await(runId, Duration.ofSeconds(10));
            assertThat(agent1.runs().plan(runId)).isPresent();
        }

        // Reopen new HaifaAgent instance with the same shared store
        try (HaifaAgent agent2 = HaifaAgents.builder(SdkTestFixtures.profile("p-persist"))
                .model(SdkTestFixtures.modelContribution())
                .persistence(new InMemoryPersistenceContribution(ports))
                .conversation(new InMemoryConversationContribution())
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

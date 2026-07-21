package io.haifa.agent.core;

import static io.haifa.agent.core.CoreTestFixtures.NOW;
import static io.haifa.agent.core.CoreTestFixtures.runSpec;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.InteractionRequestRef;
import io.haifa.agent.core.run.AgentInvocationMode;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunOutcome;
import io.haifa.agent.core.run.AgentRunResult;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.run.AgentRunUsageDelta;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentRunTest {

    @Test
    void followsTheCompleteLifecycleThroughExplicitDomainBehaviors() {
        AgentRun run = AgentRun.createRoot(new AgentRunId("run-1"), runSpec(2), NOW);

        run.markQueued(NOW.plusSeconds(1));
        run.start(NOW.plusSeconds(2));
        run.requestSuspend(NOW.plusSeconds(3));
        run.suspend(NOW.plusSeconds(4));
        run.resume(NOW.plusSeconds(5));
        run.waitForInteraction(new InteractionRequestRef("interaction-1", "clarification"), NOW.plusSeconds(6));
        run.resume(NOW.plusSeconds(7));
        run.waitForApproval(new InteractionRequestRef("approval-1", "tool-approval"), NOW.plusSeconds(8));
        run.resume(NOW.plusSeconds(9));
        run.beginCompleting(NOW.plusSeconds(10));
        AgentRunResult result = new AgentRunResult(
                AgentRunOutcome.SUCCESS,
                "Project inspection completed",
                "io.haifa.agent.coding-result",
                "1.0",
                Map.of("changed", true),
                List.of(),
                List.of());
        run.complete(result, NOW.plusSeconds(11));

        assertThat(run.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(run.status().isTerminal()).isTrue();
        assertThat(run.result()).contains(result);
        assertThat(run.error()).isEmpty();
        assertThat(run.agentDefinitionVersion().toString()).isEqualTo("1.2.0");
        assertThat(run.configurationSnapshot().snapshotId()).isEqualTo("snapshot-1");
        assertThat(run.version()).isEqualTo(11);
    }

    @Test
    void preventsBypassingCompletingAndChangingTerminalHistory() {
        AgentRun run = AgentRun.createRoot(new AgentRunId("run-1"), runSpec(2), NOW);
        run.start(NOW.plusSeconds(1));
        AgentRunResult result =
                new AgentRunResult(AgentRunOutcome.SUCCESS, "done", "result", "1.0", Map.of(), List.of(), List.of());

        assertThatThrownBy(() -> run.complete(result, NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RUNNING")
                .hasMessageContaining("COMPLETED");

        run.fail(CoreTestFixtures.error(NOW.plusSeconds(2)), NOW.plusSeconds(2));
        assertThatThrownBy(() -> run.resume(NOW.plusSeconds(3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAILED");
    }

    @Test
    void modelsRootAndChildRunsWithoutAParallelSubAgentAggregate() {
        AgentRun root = AgentRun.createRoot(new AgentRunId("root"), runSpec(2), NOW);
        AgentRun child = AgentRun.createChild(
                new AgentRunId("child"), root, AgentInvocationMode.AGENT_AS_TOOL, runSpec(2), NOW.plusSeconds(1));
        AgentRun grandchild = AgentRun.createChild(
                new AgentRunId("grandchild"), child, AgentInvocationMode.FORK_JOIN, runSpec(2), NOW.plusSeconds(2));

        assertThat(root.rootRunId()).isEqualTo(root.id());
        assertThat(root.parentRunId()).isEmpty();
        assertThat(child.rootRunId()).isEqualTo(root.id());
        assertThat(child.parentRunId()).contains(root.id());
        assertThat(child.depth()).isEqualTo(1);
        assertThat(grandchild.depth()).isEqualTo(2);
        assertThatThrownBy(() -> AgentRun.createChild(
                        new AgentRunId("too-deep"),
                        grandchild,
                        AgentInvocationMode.AGENT_AS_TOOL,
                        runSpec(2),
                        NOW.plusSeconds(3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("depth");
    }

    @Test
    void accumulatesUsageMonotonicallyAndDetectsBudgetExceeded() {
        AgentRun run = AgentRun.createRoot(new AgentRunId("run-usage"), runSpec(1), NOW);

        run.recordUsage(new AgentRunUsageDelta(900, 20, 0, 1, 2, 0, 100, 500));
        assertThat(run.budget().isExceededBy(run.usage())).isFalse();
        run.recordUsage(new AgentRunUsageDelta(101, 0, 0, 0, 0, 0, 0, 0));

        assertThat(run.usage().inputTokens()).isEqualTo(1001);
        assertThat(run.budget().isExceededBy(run.usage())).isTrue();
        assertThatThrownBy(() -> new AgentRunUsageDelta(-1, 0, 0, 0, 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

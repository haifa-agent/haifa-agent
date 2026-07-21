package io.haifa.agent.core;

import static io.haifa.agent.core.CoreTestFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.checkpoint.Checkpoint;
import io.haifa.agent.core.checkpoint.CheckpointId;
import io.haifa.agent.core.checkpoint.CheckpointStatus;
import io.haifa.agent.core.checkpoint.CheckpointType;
import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.plan.AgentPlan;
import io.haifa.agent.core.plan.AgentPlanId;
import io.haifa.agent.core.plan.TodoItem;
import io.haifa.agent.core.plan.TodoItemId;
import io.haifa.agent.core.plan.TodoPriority;
import io.haifa.agent.core.plan.TodoStatus;
import io.haifa.agent.core.reference.CheckpointPayloadRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CoreModelTest {

    @Test
    void messageUsesOrderedImmutableContentParts() {
        List<ContentPart> contents = new ArrayList<>(List.of(new TextPart("hello", "markdown")));
        AgentMessage message = new AgentMessage(
                new AgentMessageId("message-1"),
                new AgentSessionId("session-1"),
                Optional.of(new AgentRunId("run-1")),
                Optional.empty(),
                MessageRole.USER,
                MessageStatus.COMPLETED,
                MessageVisibility.USER_VISIBLE,
                1,
                contents,
                Map.of(),
                NOW);
        contents.add(new TextPart("later mutation", "plain"));

        assertThat(message.contents()).hasSize(1);
        assertThat(message.contents().getFirst().contentType()).isEqualTo("text");
        assertThatThrownBy(() -> message.contents().add(new TextPart("x", "plain")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void toolCallValidatesArgumentsAndControlsExecutionLifecycle() {
        Map<String, Object> values = new HashMap<>();
        values.put("path", "README.md");
        ToolArguments arguments = new ToolArguments("tool.file-read.input", "1.0", values);
        ToolCall call = new ToolCall(
                new ToolCallId("tool-call-1"),
                new AgentRunId("run-1"),
                new AgentStepId("step-1"),
                "file.read",
                "1.0.0",
                arguments,
                NOW);
        values.put("path", "secret.txt");

        call.beginValidation();
        call.beginPolicyCheck();
        call.waitForApproval();
        call.approve();
        call.start(NOW.plusSeconds(1));
        call.complete(
                new ToolResult(true, "read", Map.of("lines", 3), List.of(), List.of(), false), NOW.plusSeconds(2));

        assertThat(call.status()).isEqualTo(ToolCallStatus.COMPLETED);
        assertThat(call.arguments().values()).containsEntry("path", "README.md");
        assertThatThrownBy(() -> call.cancel(NOW.plusSeconds(3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal");
    }

    @Test
    void todoCannotStartUntilDependenciesAreCompletedAndPlanRevisionsAreMonotonic() {
        TodoItemId prerequisiteId = new TodoItemId("todo-1");
        TodoItem dependent = new TodoItem(
                new TodoItemId("todo-2"),
                "Run tests",
                "Verify all changes",
                TodoPriority.HIGH,
                List.of(prerequisiteId));

        assertThatThrownBy(() -> dependent.start(Set.of(), NOW)).isInstanceOf(IllegalStateException.class);
        dependent.start(Set.of(prerequisiteId), NOW);
        dependent.complete("All tests passed", NOW.plusSeconds(1));

        AgentPlan plan = new AgentPlan(
                new AgentPlanId("plan-1"), new AgentRunId("run-1"), "Implement core", List.of(dependent), NOW);
        plan.revise("Implement and verify core", List.of(dependent), NOW.plusSeconds(2));

        assertThat(dependent.status()).isEqualTo(TodoStatus.COMPLETED);
        assertThat(plan.revision()).isEqualTo(2);
    }

    @Test
    void checkpointIsAnImmutablePayloadReferenceWithMonotonicSequence() {
        Checkpoint checkpoint = new Checkpoint(
                new CheckpointId("checkpoint-1"),
                new AgentRunId("run-1"),
                Optional.of(new AgentStepId("step-1")),
                CheckpointType.AUTOMATIC,
                CheckpointStatus.VERIFIED,
                1,
                new CheckpointPayloadRef("object-store", "checkpoint/run-1/1", "run-checkpoint", "1.0"),
                "sha256:def",
                NOW);

        assertThat(checkpoint.sequence()).isEqualTo(1);
        assertThatThrownBy(() -> new Checkpoint(
                        new CheckpointId("bad"),
                        new AgentRunId("run-1"),
                        Optional.empty(),
                        CheckpointType.MANUAL,
                        CheckpointStatus.CREATED,
                        0,
                        checkpoint.payload(),
                        "hash",
                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sequence");
    }
}

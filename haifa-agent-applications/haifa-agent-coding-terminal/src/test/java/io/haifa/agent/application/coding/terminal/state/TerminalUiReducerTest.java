package io.haifa.agent.application.coding.terminal.state;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.coding.terminal.event.TerminalUiAction;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPayloads;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class TerminalUiReducerTest {
    private final TerminalUiReducer reducer = new TerminalUiReducer();

    @Test
    void projectsCommittedEventsOnceAndAdvancesCursorOnlyAfterProjection() {
        TerminalUiState initial = TerminalUiState.initial(120, 40);
        AgentRunEvent event = event(1, "event-1", new RunEventPayloads.AssistantTextDelta("g-1", "hello"));

        TerminalUiState projected = reducer.reduce(initial, new TerminalUiAction.RunEventReceived(event));
        TerminalUiState duplicate = reducer.reduce(projected, new TerminalUiAction.RunEventReceived(event));

        assertThat(projected.transcript()).hasSize(1);
        assertThat(projected.transcript().getFirst().body()).isEqualTo("hello");
        assertThat(projected.appliedCursor()).contains(event.cursor());
        assertThat(duplicate).isSameAs(projected);
    }

    @Test
    void failsClosedForOutOfOrderEvents() {
        TerminalUiState state = reducer.reduce(
                TerminalUiState.initial(120, 40),
                new TerminalUiAction.RunEventReceived(
                        event(2, "event-2", new RunEventPayloads.AssistantTextDelta("g-1", "later"))));
        TerminalUiState failed = reducer.reduce(
                state,
                new TerminalUiAction.RunEventReceived(
                        event(1, "event-1", new RunEventPayloads.AssistantTextDelta("g-1", "earlier"))));

        assertThat(failed.recoverableError()).contains("EVENT_OUT_OF_ORDER");
        assertThat(failed.appliedCursor()).isEqualTo(state.appliedCursor());
    }

    @Test
    void selectorDoesNotDestroyEditorBuffer() {
        TerminalUiState edited =
                reducer.reduce(TerminalUiState.initial(80, 24), new TerminalUiAction.EditorChanged("draft", 5));
        TerminalUiState selected = reducer.reduce(
                edited,
                new TerminalUiAction.SelectorOpened(new TerminalSelector("resume", "Resume", List.of("session"), 0)));
        TerminalUiState closed = reducer.reduce(selected, new TerminalUiAction.SelectorClosed());

        assertThat(closed.editorBuffer()).isEqualTo("draft");
        assertThat(closed.editorCursor()).isEqualTo(5);
        assertThat(closed.selector()).isEmpty();
    }

    private static AgentRunEvent event(long sequence, String id, AgentRunEvent.Payload payload) {
        AgentRunId runId = new AgentRunId("run-1");
        return new AgentRunEvent(
                id,
                payload instanceof RunEventPayloads.AssistantTextDelta ? "assistant.text.delta" : "run.status.changed",
                "1",
                runId,
                new AgentSessionId("session-1"),
                sequence,
                new RunEventCursor(runId, "1", OptionalLong.of(sequence)),
                Instant.parse("2026-07-27T00:00:00Z"),
                Optional.empty(),
                Optional.empty(),
                payload);
    }
}

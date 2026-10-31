package io.haifa.agent.application.coding.terminal.state;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.coding.terminal.event.TerminalUiAction;
import io.haifa.agent.application.project.product.coding.CodingSessionSummary;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.session.AgentSessionStatus;
import io.haifa.agent.project.domain.ProjectId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Sidebar task-list behaviour for 42-B.
 *
 * <p>These assertions cover what the sidebar contributes on its own: rows are Sessions, the list is boundedly truncated,
 * a refresh never disturbs the transcript or the Run, and the cursor cannot drift outside the rendered rows. Run
 * isolation between two live Sessions is proven by the process acceptance harness, not here.
 */
class TerminalSessionRowTest {
    private final TerminalUiReducer reducer = new TerminalUiReducer();
    private final ProjectId project = new ProjectId("project-42b");

    private CodingSessionSummary summary(
            String id, String name, AgentSessionStatus status, Optional<AgentRunStatus> runStatus, int queued) {
        return new CodingSessionSummary(
                new AgentSessionId(id),
                project,
                name,
                status,
                runStatus.map(ignored -> new AgentRunId("run-" + id)),
                runStatus,
                queued,
                Instant.parse("2026-10-31T00:00:00Z"),
                1L);
    }

    @Test
    void rowsAreSessionsAndCarryTheAuthoritativeStatus() {
        TerminalSessionRow running = TerminalSessionRow.from(
                summary("s-1", "Refactor host", AgentSessionStatus.ACTIVE, Optional.of(AgentRunStatus.RUNNING), 0));
        TerminalSessionRow waiting = TerminalSessionRow.from(summary(
                "s-2", "Await approval", AgentSessionStatus.ACTIVE, Optional.of(AgentRunStatus.WAITING_APPROVAL), 3));
        TerminalSessionRow idle =
                TerminalSessionRow.from(summary("s-3", "Old work", AgentSessionStatus.ACTIVE, Optional.empty(), 0));

        assertThat(running.statusLabel()).isEqualTo("RUNNING");
        assertThat(running.active()).isTrue();
        // A Run waiting on a human must be distinguishable from a working Run, otherwise switching hides the prompt.
        assertThat(waiting.statusLabel()).isEqualTo("WAITING APPROVAL");
        assertThat(waiting.awaitingHuman()).isTrue();
        assertThat(waiting.queuedCount()).isEqualTo(3);
        // Without an active Run the Session status is authoritative, and the row is history rather than active.
        assertThat(idle.active()).isFalse();
        assertThat(idle.statusLabel()).isEqualTo("ACTIVE");
        assertThat(idle.awaitingHuman()).isFalse();
    }

    @Test
    void queuedCountIsRenderedOnlyWhenPositive() {
        TerminalSessionRow quiet = TerminalSessionRow.from(
                summary("s-1", "Quiet", AgentSessionStatus.ACTIVE, Optional.of(AgentRunStatus.RUNNING), 0));
        TerminalSessionRow busy = TerminalSessionRow.from(
                summary("s-2", "Busy", AgentSessionStatus.ACTIVE, Optional.of(AgentRunStatus.RUNNING), 2));

        assertThat(quiet.render(false)).doesNotContain("+");
        assertThat(busy.render(false)).contains("+2");
        // The marker has to survive clipping for the selected row to stay identifiable.
        assertThat(busy.render(true)).startsWith("\u25B8");
        assertThat(busy.render(false)).startsWith("  ");
    }

    @Test
    void sessionRowsAreBoundedlyTruncatedWithoutPagination() {
        List<TerminalSessionRow> many = new ArrayList<>();
        for (int index = 0; index < TerminalUiState.MAX_SESSION_ROWS + 20; index++) {
            many.add(TerminalSessionRow.from(
                    summary("s-" + index, "Session " + index, AgentSessionStatus.ACTIVE, Optional.empty(), 0)));
        }

        TerminalUiState state = TerminalUiState.initial(140, 40).withSessionRows(many);

        // v1 truncates at the cap instead of paging (D6); the drop is silent and bounded, never unbounded growth.
        assertThat(state.sessionRows()).hasSize(TerminalUiState.MAX_SESSION_ROWS);
        assertThat(state.sessionRows().getFirst().sessionId().value()).isEqualTo("s-0");
    }

    @Test
    void aRefreshKeepsSelectionInsideTheRenderedRows() {
        TerminalUiState state = TerminalUiState.initial(140, 40);
        List<TerminalSessionRow> five = List.of(
                TerminalSessionRow.from(
                        summary("s-1", "A", AgentSessionStatus.ACTIVE, Optional.of(AgentRunStatus.RUNNING), 0)),
                TerminalSessionRow.from(
                        summary("s-2", "B", AgentSessionStatus.ACTIVE, Optional.of(AgentRunStatus.RUNNING), 0)),
                TerminalSessionRow.from(
                        summary("s-3", "C", AgentSessionStatus.ACTIVE, Optional.of(AgentRunStatus.RUNNING), 0)),
                TerminalSessionRow.from(
                        summary("s-4", "D", AgentSessionStatus.ACTIVE, Optional.of(AgentRunStatus.RUNNING), 0)),
                TerminalSessionRow.from(
                        summary("s-5", "E", AgentSessionStatus.ACTIVE, Optional.of(AgentRunStatus.RUNNING), 0)));

        state = reducer.reduce(state, new TerminalUiAction.SessionRowsLoaded(five));
        state = reducer.reduce(state, new TerminalUiAction.SessionRowMoved(4));
        assertThat(state.sessionRowSelected()).isEqualTo(4);

        // A shorter refresh must not leave the cursor past the end, which would make the sidebar index invalid.
        state = reducer.reduce(state, new TerminalUiAction.SessionRowsLoaded(five.subList(0, 2)));
        assertThat(state.sessionRowSelected()).isEqualTo(1);
        assertThat(state.selectedSessionRow()).isPresent();
    }

    @Test
    void movementIsClampedAndNeverWraps() {
        TerminalUiState state = TerminalUiState.initial(140, 40)
                .withSessionRows(List.of(
                        TerminalSessionRow.from(summary("s-1", "A", AgentSessionStatus.ACTIVE, Optional.empty(), 0)),
                        TerminalSessionRow.from(summary("s-2", "B", AgentSessionStatus.ACTIVE, Optional.empty(), 0))));

        // Up at the top and down at the bottom must stay put: wrapping would silently select the opposite Session.
        assertThat(state.withSessionRowMoved(-5).sessionRowSelected()).isZero();
        assertThat(state.withSessionRowMoved(9).sessionRowSelected()).isEqualTo(1);
        assertThat(TerminalUiState.initial(140, 40).withSessionRowMoved(1).sessionRowSelected())
                .isZero();
    }

    @Test
    void aSidebarRefreshDoesNotTouchTheTranscriptOrSelection() {
        TerminalUiState initial = TerminalUiState.initial(140, 40);
        TerminalUiState withTranscript =
                reducer.reduce(initial, new TerminalUiAction.UserMessageCommitted("m-1", "keep this turn"));

        TerminalUiState refreshed = reducer.reduce(
                withTranscript,
                new TerminalUiAction.SessionRowsLoaded(List.of(TerminalSessionRow.from(
                        summary("s-1", "A", AgentSessionStatus.ACTIVE, Optional.of(AgentRunStatus.RUNNING), 0)))));

        // The sidebar is an observation surface: a poll result must never rewrite the conversation or the Run view.
        assertThat(refreshed.transcript()).hasSameSizeAs(withTranscript.transcript());
        assertThat(refreshed.session()).isEqualTo(withTranscript.session());
        assertThat(refreshed.currentRunId()).isEqualTo(withTranscript.currentRunId());
        assertThat(refreshed.sessionRows()).hasSize(1);
    }

    @Test
    void waitingSessionsSortFirstThenActiveThenRecentHistory() {
        java.util.List<CodingSessionSummary> unordered = List.of(
                // Deliberately ordered worst-case so a stable input cannot accidentally satisfy the assertion.
                summary("s-old", "Old history", AgentSessionStatus.ACTIVE, Optional.empty(), 0),
                summary("s-run", "Live work", AgentSessionStatus.ACTIVE, Optional.of(AgentRunStatus.RUNNING), 0),
                summary(
                        "s-wait",
                        "Needs approval",
                        AgentSessionStatus.ACTIVE,
                        Optional.of(AgentRunStatus.WAITING_APPROVAL),
                        0));

        List<TerminalSessionRow> ordered = TerminalSessionRow.ordered(unordered);

        // A Session blocked on a human is the only actionable row, so it must not be buried by active or idle ones.
        assertThat(ordered).hasSize(3);
        assertThat(ordered.get(0).sessionId().value()).isEqualTo("s-wait");
        assertThat(ordered.get(1).sessionId().value()).isEqualTo("s-run");
        assertThat(ordered.get(2).sessionId().value()).isEqualTo("s-old");
    }

    @Test
    void orderingIsCappedSoTruncationKeepsActionableRows() {
        java.util.List<CodingSessionSummary> many = new ArrayList<>();
        // The waiting Session is appended last, so only an order-then-truncate pipeline keeps it in the list.
        for (int index = 0; index < TerminalUiState.MAX_SESSION_ROWS + 10; index++) {
            many.add(summary("s-" + index, "Session " + index, AgentSessionStatus.ACTIVE, Optional.empty(), 0));
        }
        many.add(summary(
                "s-wait",
                "Needs approval",
                AgentSessionStatus.ACTIVE,
                Optional.of(AgentRunStatus.WAITING_APPROVAL),
                0));

        List<TerminalSessionRow> ordered = TerminalSessionRow.ordered(many);

        assertThat(ordered).hasSize(TerminalUiState.MAX_SESSION_ROWS);
        assertThat(ordered.getFirst().sessionId().value()).isEqualTo("s-wait");
    }

    @Test
    void emptySidebarHasNoSelectableRow() {
        TerminalUiState state = TerminalUiState.initial(140, 40);

        assertThat(state.sessionRows()).isEmpty();
        assertThat(state.selectedSessionRow()).isEmpty();
        assertThat(state.sessionRowSelected()).isZero();
    }
}

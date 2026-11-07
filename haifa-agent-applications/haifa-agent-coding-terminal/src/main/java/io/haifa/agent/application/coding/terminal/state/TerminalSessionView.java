package io.haifa.agent.application.coding.terminal.state;

import io.haifa.agent.application.project.product.coding.CodingModelSelection;
import io.haifa.agent.application.project.product.coding.CodingSessionSummary;
import io.haifa.agent.application.project.product.coding.CodingSessionView;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import java.util.Optional;

/** Session metadata needed by the terminal. Transport-specific interaction objects stay at the old entry. */
public record TerminalSessionView(
        CodingSessionSummary summary,
        Optional<Run> activeRun,
        Optional<Pending> pendingInteraction,
        String productProfileRef,
        CodingModelSelection model,
        Optional<String> activeRunTaskSummary) {

    public record Run(AgentRunId runId, AgentRunStatus status, long version) {}

    public record Pending(AgentRunId runId, String requestId) {}

    public static TerminalSessionView fromLegacy(CodingSessionView view) {
        return new TerminalSessionView(
                view.summary(),
                view.activeRun().map(run -> new Run(run.runId(), run.status(), run.version())),
                view.pendingInteraction()
                        .map(value ->
                                new Pending(value.runId(), value.requestId().value())),
                view.productProfileRef(),
                view.model(),
                view.activeRunTaskSummary());
    }
}

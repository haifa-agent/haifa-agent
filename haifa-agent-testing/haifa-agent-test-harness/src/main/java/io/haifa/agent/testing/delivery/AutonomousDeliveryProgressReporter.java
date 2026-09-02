package io.haifa.agent.testing.delivery;

import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.RunEventPayloads;
import java.util.Objects;
import java.util.function.Consumer;

/** Projects only safe lifecycle metadata from a live Coding Agent run to the invoking console. */
final class AutonomousDeliveryProgressReporter {
    private final Consumer<String> output;

    AutonomousDeliveryProgressReporter(Consumer<String> output) {
        this.output = Objects.requireNonNull(output, "output must not be null");
    }

    void phaseStarted(String phase) {
        emit("phase=" + phase + " status=STARTED");
    }

    void phaseCompleted(String phase, boolean successful) {
        emit("phase=" + phase + " status=" + (successful ? "PASSED" : "FAILED"));
    }

    void caseStarted(String caseId, int repetition) {
        emit("case=" + caseId + " repetition=" + repetition + " status=STARTED");
    }

    void caseCompleted(String caseId, int repetition, boolean acceptancePassed, boolean gatePassed, long elapsedMillis) {
        emit("case="
                + caseId
                + " repetition="
                + repetition
                + " acceptance="
                + (acceptancePassed ? "PASSED" : "FAILED")
                + " gate="
                + (gatePassed ? "PASSED" : "FAILED")
                + " elapsedMillis="
                + elapsedMillis);
    }

    void eventFeedUnavailable() {
        emit("event-feed status=UNAVAILABLE; continuing without live event details");
    }

    void project(AgentRunEvent event) {
        if (event != null) {
            project(event.payload());
        }
    }

    void project(AgentRunEvent.Payload payload) {
        if (payload instanceof RunEventPayloads.RunLifecycle lifecycle) {
            emit("run status=" + lifecycle.status() + " reason=" + lifecycle.reasonCode());
        } else if (payload instanceof RunEventPayloads.ModelLifecycle lifecycle) {
            emit("model iteration="
                    + lifecycle.iteration()
                    + " attempt="
                    + lifecycle.attempt()
                    + " status="
                    + lifecycle.status()
                    + " inputTokens="
                    + lifecycle.inputTokens()
                    + " outputTokens="
                    + lifecycle.outputTokens()
                    + " reason="
                    + lifecycle.reasonCode());
        } else if (payload instanceof RunEventPayloads.ToolLifecycle lifecycle) {
            emit("tool="
                    + lifecycle.displayName()
                    + " status="
                    + lifecycle.status()
                    + " reason="
                    + lifecycle.reasonCode());
        } else if (payload instanceof RunEventPayloads.InteractionLifecycle lifecycle) {
            emit("interaction kind="
                    + lifecycle.kind()
                    + " state="
                    + lifecycle.state()
                    + " action="
                    + lifecycle.actionOrReason());
        }
    }

    private void emit(String detail) {
        output.accept("[delivery] " + detail);
    }
}

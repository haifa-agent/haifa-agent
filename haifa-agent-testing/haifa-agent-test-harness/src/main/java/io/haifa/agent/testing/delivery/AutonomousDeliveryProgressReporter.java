package io.haifa.agent.testing.delivery;

import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.RunEventPayloads;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Projects only safe lifecycle metadata from a live Coding Agent run to the invoking console. */
final class AutonomousDeliveryProgressReporter {
    private final Consumer<String> output;
    private final String phase;
    private final int totalEvaluations;
    private final Map<String, Integer> repetitionsByCase;
    private int evaluated;
    private int passed;
    private String currentCase = "-";
    private String currentRepetition = "-";

    AutonomousDeliveryProgressReporter(Consumer<String> output, AutonomousDeliverySuiteManifest suite) {
        this.output = Objects.requireNonNull(output, "output must not be null");
        Objects.requireNonNull(suite, "suite must not be null");
        this.phase = suite.phase();
        this.totalEvaluations = suite.cases().stream()
                .mapToInt(AutonomousDeliverySuiteManifest.CaseSelection::repetitions)
                .sum();
        this.repetitionsByCase = suite.cases().stream()
                .collect(Collectors.toUnmodifiableMap(
                        AutonomousDeliverySuiteManifest.CaseSelection::caseId,
                        AutonomousDeliverySuiteManifest.CaseSelection::repetitions));
    }

    void phaseStarted(String phase) {
        requirePhase(phase);
        emit("phase=" + phase + " status=STARTED");
        emitProgress();
    }

    void phaseCompleted(String phase, boolean successful) {
        requirePhase(phase);
        emit("phase=" + phase + " status=" + (successful ? "PASSED" : "FAILED"));
    }

    void caseStarted(String caseId, int repetition) {
        int repetitions = repetitionsFor(caseId);
        if (repetition < 1 || repetition > repetitions) {
            throw new IllegalArgumentException("case repetition is outside the suite selection");
        }
        currentCase = caseId;
        currentRepetition = repetition + "/" + repetitions;
        emitCurrent("status=STARTED");
        emitProgress();
    }

    void caseCompleted(
            String caseId, int repetition, boolean acceptancePassed, boolean gatePassed, long elapsedMillis) {
        int repetitions = repetitionsFor(caseId);
        emit("phase="
                + phase
                + " case="
                + caseId
                + " repetition="
                + repetition
                + "/"
                + repetitions
                + " acceptance="
                + (acceptancePassed ? "PASSED" : "FAILED")
                + " gate="
                + (gatePassed ? "PASSED" : "FAILED")
                + " elapsedMillis="
                + elapsedMillis);
        evaluated++;
        if (gatePassed) {
            passed++;
        }
        currentCase = "-";
        currentRepetition = "-";
        emitProgress();
    }

    void eventFeedUnavailable() {
        emitCurrent("event-feed status=UNAVAILABLE; continuing without live event details");
    }

    void project(AgentRunEvent event) {
        if (event != null) {
            project(event.payload());
        }
    }

    void project(AgentRunEvent.Payload payload) {
        if (payload instanceof RunEventPayloads.RunLifecycle lifecycle) {
            emitCurrent("run status=" + lifecycle.status() + " reason=" + lifecycle.reasonCode());
        } else if (payload instanceof RunEventPayloads.ModelLifecycle lifecycle) {
            emitCurrent("model iteration="
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
            emitCurrent("tool="
                    + lifecycle.displayName()
                    + " status="
                    + lifecycle.status()
                    + " reason="
                    + lifecycle.reasonCode());
        } else if (payload instanceof RunEventPayloads.InteractionLifecycle lifecycle) {
            emitCurrent("interaction kind="
                    + lifecycle.kind()
                    + " state="
                    + lifecycle.state()
                    + " action="
                    + lifecycle.actionOrReason());
        }
    }

    private int repetitionsFor(String caseId) {
        Integer repetitions = repetitionsByCase.get(caseId);
        if (repetitions == null) {
            throw new IllegalArgumentException("case is not selected by the suite");
        }
        return repetitions;
    }

    private void requirePhase(String value) {
        if (!phase.equals(value)) {
            throw new IllegalArgumentException("progress phase does not match the suite");
        }
    }

    private void emitCurrent(String detail) {
        emit("phase=" + phase + " case=" + currentCase + " repetition=" + currentRepetition + " " + detail);
    }

    private void emitProgress() {
        output.accept("[delivery-progress] phase="
                + phase
                + " evaluated="
                + evaluated
                + "/"
                + totalEvaluations
                + " passed="
                + passed
                + "/"
                + evaluated
                + " currentCase="
                + currentCase
                + " currentRepetition="
                + currentRepetition);
    }

    private void emit(String detail) {
        output.accept("[delivery] " + detail);
    }
}

package io.haifa.agent.application.coding.terminal.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.agent.runtime.api.display.BoundedText;
import io.haifa.agent.runtime.api.display.ToolDisplayBudget;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ToolCallDisplayProjectionTest {

    private static final ToolDisplayBudget BUDGET = new ToolDisplayBudget(1_024, 20);

    @Test
    void projectsNormalSuccessWithBoundedTargetOutputAndReference() {
        ToolCallDisplayProjection display = ToolCallDisplayProjection.from(
                lifecycle("tool-1", "execution_run", "SUCCEEDED", "NONE", "git status", "asset-1"),
                Optional.of("On branch main"),
                BUDGET);

        assertThat(display.toolCallId()).isEqualTo("tool-1");
        assertThat(display.toolName()).isEqualTo("execution_run");
        assertThat(display.status()).isEqualTo("SUCCEEDED");
        assertThat(display.outcomeUnknown()).isFalse();
        assertThat(display.target()).isEqualTo("git status");
        assertThat(display.reasonCode()).isEmpty();
        assertThat(display.outputPreview()).hasValueSatisfying(preview -> {
            assertThat(preview.text()).isEqualTo("On branch main");
            assertThat(preview.truncated()).isFalse();
        });
        assertThat(display.outputReference()).contains("asset-1");
    }

    @Test
    void boundsLongOutputToHeadAndTailWhileKeepingOriginalCounts() {
        String output = "line\n".repeat(500);

        ToolCallDisplayProjection display = ToolCallDisplayProjection.from(
                lifecycle("tool-1", "execution_run", "SUCCEEDED", "NONE", "cat log", "asset-1"),
                Optional.of(output),
                new ToolDisplayBudget(10_000, 4));

        assertThat(display.outputPreview()).hasValueSatisfying(preview -> {
            assertThat(preview.truncated()).isTrue();
            assertThat(preview.truncationReason()).contains(BoundedText.TruncationReason.OUTPUT_LINES);
            assertThat(preview.byteCount()).isEqualTo(output.getBytes(StandardCharsets.UTF_8).length);
            assertThat(preview.lineCount()).isEqualTo(501);
            assertThat(preview.text().length()).isLessThan(output.length());
        });
    }

    @Test
    void projectsRuntimeObservationPreviewAndTypedExecutionMetadata() {
        RunEventPayloads.ToolLifecycle lifecycle = new RunEventPayloads.ToolLifecycle(
                "tool-1",
                "execution_run",
                "SUCCEEDED",
                "NONE",
                "git status",
                "asset-1",
                Optional.of(new RunEventPayloads.ToolObservation(
                        Optional.of(BoundedText.of("Command exited (exit 0)", BUDGET)),
                        Optional.of("EXITED"),
                        Optional.of(0))));

        ToolCallDisplayProjection display = ToolCallDisplayProjection.from(lifecycle, BUDGET);

        assertThat(display.outputPreview())
                .hasValueSatisfying(preview -> assertThat(preview.text()).isEqualTo("Command exited (exit 0)"));
        assertThat(display.processState()).contains("EXITED");
        assertThat(display.exitCode()).contains(0);
    }

    @Test
    void preservesOrdinaryFailureReasonWithoutMarkingOutcomeUnknown() {
        ToolCallDisplayProjection display = ToolCallDisplayProjection.from(
                lifecycle("tool-1", "workspace.read", "FAILED", "IO_FAILED", "a.txt", ""), BUDGET);

        assertThat(display.outcomeUnknown()).isFalse();
        assertThat(display.reasonCode()).contains("IO_FAILED");
        assertThat(display.outputPreview()).isEmpty();
    }

    @Test
    void preservesUnknownOutcomeStatusAndReason() {
        ToolCallDisplayProjection display = ToolCallDisplayProjection.from(
                lifecycle("tool-1", "execution_run", "OUTCOME_UNKNOWN", "AUTOMATIC_REPLAY_FORBIDDEN", "rm -rf", ""),
                BUDGET);

        assertThat(display.outcomeUnknown()).isTrue();
        assertThat(display.status()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(display.displayStatus()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(display.reasonCode()).contains("AUTOMATIC_REPLAY_FORBIDDEN");
    }

    @Test
    void collapsesAuthoritativeFailureWithUnknownOutcomeReasonToProductDisplayStatus() {
        ToolCallDisplayProjection display = ToolCallDisplayProjection.from(
                lifecycle("tool-1", "execution_run", "FAILED", "TOOL_OUTCOME_UNKNOWN", "rm -rf", "asset-1"), BUDGET);

        assertThat(display.outcomeUnknown()).isTrue();
        assertThat(display.status()).isEqualTo("FAILED");
        assertThat(display.displayStatus()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(display.reasonCode()).contains("TOOL_OUTCOME_UNKNOWN");
    }

    @Test
    void projectsCancelledAndTimedOutStatusesWithBoundedObservationButNoUnknownFlag() {
        for (String status : List.of("CANCELLED", "TIMEOUT")) {
            ToolCallDisplayProjection display = ToolCallDisplayProjection.from(
                    new RunEventPayloads.ToolLifecycle(
                            "tool-1",
                            "execution_run",
                            status,
                            "TOOL_" + status,
                            "git status",
                            "asset-1",
                            Optional.of(new RunEventPayloads.ToolObservation(
                                    Optional.of(BoundedText.of("partial output", BUDGET)),
                                    Optional.of("EXITED"),
                                    Optional.of(1)))),
                    BUDGET);

            assertThat(display.status()).isEqualTo(status);
            assertThat(display.displayStatus()).isEqualTo(status);
            assertThat(display.outcomeUnknown()).isFalse();
            assertThat(display.outputPreview())
                    .hasValueSatisfying(preview -> assertThat(preview.text()).isEqualTo("partial output"));
            assertThat(display.processState()).contains("EXITED");
            assertThat(display.exitCode()).contains(1);
        }
    }

    @Test
    void treatsStableUnknownOutcomeCodeAsUnknown() {
        assertThat(ToolCallDisplayProjection.isOutcomeUnknown("FAILED", "TOOL_OUTCOME_UNKNOWN"))
                .isTrue();
        assertThat(ToolCallDisplayProjection.isOutcomeUnknown("FAILED", "IO_FAILED"))
                .isFalse();
    }

    @Test
    void boundsLongTargetWithoutSplittingSurrogatePairs() {
        String target = "x".repeat(300) + "😀tail";

        ToolCallDisplayProjection display = ToolCallDisplayProjection.from(
                lifecycle("tool-1", "workspace.read", "SUCCEEDED", "NONE", target, ""), BUDGET);

        assertThat(display.target()).hasSize(256).endsWith("…");
        assertThat(display.target().chars().anyMatch(value -> Character.isSurrogate((char) value)))
                .isFalse();
    }

    @Test
    void rejectsBlankIdentity() {
        assertThatThrownBy(() -> ToolCallDisplayProjection.from(
                        lifecycle("", "execution_run", "SUCCEEDED", "NONE", "git status", ""), BUDGET))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static RunEventPayloads.ToolLifecycle lifecycle(
            String toolCallId,
            String displayName,
            String status,
            String reasonCode,
            String targetSummary,
            String resultRef) {
        return new RunEventPayloads.ToolLifecycle(
                toolCallId, displayName, status, reasonCode, targetSummary, resultRef);
    }
}

package io.haifa.agent.personalassistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.personalassistant.application.PersonalAssistantApplication.ActivityKind;
import io.haifa.agent.personalassistant.application.PersonalAssistantApplication.ActivityView;
import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.agent.runtime.api.display.BoundedText;
import io.haifa.agent.runtime.api.display.ToolDisplayBudget;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PersonalAssistantActivityTest {
    private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");
    private static final ToolDisplayBudget BUDGET = new ToolDisplayBudget(1_024, 4);

    @Test
    void successCarriesBoundedPreviewTypedMetadataAndResultReference() {
        ActivityView activity = PersonalAssistantApplication.toolActivity(
                "event-1",
                "run-1",
                NOW,
                1L,
                ActivityKind.TOOL,
                lifecycle(
                        "tool-1",
                        "execution_run",
                        "SUCCEEDED",
                        "NONE",
                        "git status",
                        "asset-1",
                        Optional.of(new RunEventPayloads.ToolObservation(
                                Optional.of(BoundedText.of("Command exited (exit 0)", BUDGET)),
                                Optional.of("EXITED"),
                                Optional.of(0)))));

        assertThat(activity.safeResultSummary()).isEqualTo("Completed");
        assertThat(activity.completedAt()).contains(NOW);
        assertThat(activity.toolDetail()).hasValueSatisfying(detail -> {
            assertThat(detail.outputPreview()).contains("Command exited (exit 0)");
            assertThat(detail.processState()).contains("EXITED");
            assertThat(detail.exitCode()).contains(0);
            assertThat(detail.resultRef()).contains("asset-1");
            assertThat(detail.outcomeUnknown()).isFalse();
        });
    }

    @Test
    void longOutputIsBoundedWithOriginalSizeFacts() {
        String output = "line\n".repeat(500);

        ActivityView activity = PersonalAssistantApplication.toolActivity(
                "event-1",
                "run-1",
                NOW,
                1L,
                ActivityKind.TOOL,
                lifecycle(
                        "tool-1",
                        "execution_run",
                        "SUCCEEDED",
                        "NONE",
                        "cat log",
                        "",
                        Optional.of(new RunEventPayloads.ToolObservation(
                                Optional.of(BoundedText.of(output, new ToolDisplayBudget(10_000, 4))),
                                Optional.of("EXITED"),
                                Optional.of(0)))));

        assertThat(activity.toolDetail()).hasValueSatisfying(detail -> {
            assertThat(detail.truncated()).isTrue();
            assertThat(detail.byteCount()).isEqualTo(output.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            assertThat(detail.lineCount()).isEqualTo(501);
            assertThat(detail.truncationReason()).contains("OUTPUT_LINES");
            assertThat(detail.outputPreview())
                    .hasValueSatisfying(preview -> assertThat(preview).doesNotContain(output));
        });
    }

    @Test
    void failureStaysFailedAndNeverReportsSuccess() {
        ActivityView activity = PersonalAssistantApplication.toolActivity(
                "event-1",
                "run-1",
                NOW,
                1L,
                ActivityKind.TOOL,
                lifecycle("tool-1", "workspace.read", "FAILED", "IO_FAILED", "a.txt", "", Optional.empty()));

        assertThat(activity.safeResultSummary()).isEqualTo("IO_FAILED");
        assertThat(activity.toolDetail()).isEmpty();
    }

    @Test
    void unknownOutcomeStaysUnknownAndIsTerminal() {
        ActivityView activity = PersonalAssistantApplication.toolActivity(
                "event-1",
                "run-1",
                NOW,
                1L,
                ActivityKind.TOOL,
                lifecycle(
                        "tool-1",
                        "execution_run",
                        "OUTCOME_UNKNOWN",
                        "AUTOMATIC_REPLAY_FORBIDDEN",
                        "rm -rf",
                        "",
                        Optional.empty()));

        assertThat(activity.safeResultSummary()).isNotEqualTo("Completed").isEqualTo("AUTOMATIC_REPLAY_FORBIDDEN");
        assertThat(activity.completedAt()).contains(NOW);
        assertThat(activity.toolDetail()).hasValueSatisfying(detail -> assertThat(detail.outcomeUnknown())
                .isTrue());
    }

    @Test
    void cancelledAndTimedOutToolsStayTerminalWithStableIdentity() {
        for (String status : java.util.List.of("CANCELLED", "TIMEOUT")) {
            ActivityView activity = PersonalAssistantApplication.toolActivity(
                    "event-" + status,
                    "run-1",
                    NOW,
                    1L,
                    ActivityKind.TOOL,
                    lifecycle(
                            "tool-1",
                            "execution_run",
                            status,
                            "WALL_TIME_EXCEEDED",
                            "git status",
                            "",
                            Optional.empty()));

            assertThat(activity.activityId()).isEqualTo("tool:tool-1");
            assertThat(activity.safeResultSummary()).isEqualTo("WALL_TIME_EXCEEDED");
            assertThat(activity.completedAt()).contains(NOW);
        }
    }

    @Test
    void oldVersionUpdatesNeverRollBackNewerPreview() {
        ActivityView newer = PersonalAssistantApplication.toolActivity(
                "event-2",
                "run-1",
                NOW,
                5L,
                ActivityKind.TOOL,
                lifecycle("tool-1", "execution_run", "SUCCEEDED", "NONE", "git status", "", Optional.empty()));
        ActivityView stale = PersonalAssistantApplication.toolActivity(
                "event-1",
                "run-1",
                NOW,
                3L,
                ActivityKind.TOOL,
                lifecycle("tool-1", "execution_run", "STARTED", "NONE", "git status", "", Optional.empty()));

        assertThat(PersonalAssistantApplication.mergeActivity(newer, stale)).isSameAs(newer);
    }

    @Test
    void newerUpdatesKeepTheToolDetailAcrossMerges() {
        ActivityView earlier = PersonalAssistantApplication.toolActivity(
                "event-1",
                "run-1",
                NOW,
                3L,
                ActivityKind.TOOL,
                lifecycle("tool-1", "execution_run", "STARTED", "NONE", "git status", "", Optional.empty()));
        ActivityView terminal = PersonalAssistantApplication.toolActivity(
                "event-2",
                "run-1",
                NOW,
                5L,
                ActivityKind.TOOL,
                lifecycle(
                        "tool-1",
                        "execution_run",
                        "SUCCEEDED",
                        "NONE",
                        "git status",
                        "asset-1",
                        Optional.of(new RunEventPayloads.ToolObservation(
                                Optional.of(BoundedText.of("Command exited (exit 0)", BUDGET)),
                                Optional.of("EXITED"),
                                Optional.of(0)))));

        ActivityView merged = PersonalAssistantApplication.mergeActivity(earlier, terminal);
        assertThat(merged.version()).isEqualTo(5);
        assertThat(merged.toolDetail()).isEqualTo(terminal.toolDetail());
    }

    private static RunEventPayloads.ToolLifecycle lifecycle(
            String toolCallId,
            String displayName,
            String status,
            String reasonCode,
            String targetSummary,
            String resultRef,
            Optional<RunEventPayloads.ToolObservation> observation) {
        return new RunEventPayloads.ToolLifecycle(
                toolCallId, displayName, status, reasonCode, targetSummary, resultRef, observation);
    }
}

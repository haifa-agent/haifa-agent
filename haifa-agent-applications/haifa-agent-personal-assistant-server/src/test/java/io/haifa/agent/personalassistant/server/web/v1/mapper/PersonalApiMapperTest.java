package io.haifa.agent.personalassistant.server.web.v1.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.personalassistant.application.PersonalAssistantApplication.ActivityKind;
import io.haifa.agent.personalassistant.application.PersonalAssistantApplication.ActivityView;
import io.haifa.agent.personalassistant.application.PersonalAssistantApplication.ToolDetailView;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PersonalApiMapperTest {
    private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");

    private final PersonalApiMapper mapper = new PersonalApiMapper();

    @Test
    void mapsBoundedPreviewStatsAndTypedExecutionMetadata() {
        var dto = mapper.activity(activity(
                "Completed",
                new ToolDetailView(
                        Optional.of("Command exited (exit 0)"),
                        true,
                        4_800L,
                        42L,
                        Optional.of("OUTPUT_LINES"),
                        Optional.of("EXITED"),
                        Optional.of(0),
                        Optional.of("asset-1"),
                        false)));

        assertThat(dto.toolDetail()).hasValueSatisfying(detail -> {
            assertThat(detail.outputPreview()).contains("Command exited (exit 0)");
            assertThat(detail.truncated()).isTrue();
            assertThat(detail.byteCount()).isEqualTo(4_800L);
            assertThat(detail.lineCount()).isEqualTo(42L);
            assertThat(detail.truncationReason()).contains("OUTPUT_LINES");
            assertThat(detail.processState()).contains("EXITED");
            assertThat(detail.exitCode()).contains(0);
            assertThat(detail.resultRef()).contains("asset-1");
            assertThat(detail.outcomeUnknown()).isFalse();
        });
    }

    @Test
    void unknownOutcomeStaysExplicitAndIsNeverMappedToSuccess() {
        var dto = mapper.activity(activity(
                "AUTOMATIC_REPLAY_FORBIDDEN",
                new ToolDetailView(
                        Optional.empty(),
                        false,
                        0L,
                        0L,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        true)));

        assertThat(dto.safeResultSummary()).isNotEqualTo("Completed");
        assertThat(dto.toolDetail()).hasValueSatisfying(detail -> assertThat(detail.outcomeUnknown())
                .isTrue());
    }

    @Test
    void absentDetailMapsToEmptyOptional() {
        assertThat(mapper.activity(activity("Completed", null)).toolDetail()).isEmpty();
    }

    private static ActivityView activity(String resultSummary, ToolDetailView detail) {
        return new ActivityView(
                "tool:tool-1",
                "event-1",
                Optional.empty(),
                "run-1",
                ActivityKind.TOOL,
                "execution_run",
                "git status",
                "SUCCEEDED",
                Optional.of(NOW),
                Optional.of(NOW),
                Optional.of(NOW),
                NOW,
                resultSummary,
                Optional.empty(),
                5L,
                Optional.ofNullable(detail));
    }
}

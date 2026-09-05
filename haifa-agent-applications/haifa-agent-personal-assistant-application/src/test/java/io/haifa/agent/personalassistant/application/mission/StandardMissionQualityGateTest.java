package io.haifa.agent.personalassistant.application.mission;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class StandardMissionQualityGateTest {

    @Test
    void passesValidStandardResult() {
        StandardMissionQualityGate gate = new StandardMissionQualityGate();
        String answerMarkdown = "### Architecture review\n\nArchitecture review "
                + "provides a complete comparison of scalability, security, operational trade-offs, migration "
                + "steps, failure handling, observability, and rollout recommendations. The analysis preserves "
                + "the acceptance criterion Production readiness and explains the evidence behind each conclusion. "
                + "Additional implementation detail covers capacity planning, recovery behavior, compatibility, "
                + "cost controls, and the validation required before production adoption.";

        var candidate = new StandardMissionQualityGate.Candidate(
                "pa.mission-final-result/v2",
                "Summary",
                answerMarkdown,
                "COMPLETE",
                List.of("Architecture review", "Production readiness"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("https://ethereum.org"));

        StandardMissionQualityGate.Result evaluation = gate.evaluate(
                candidate,
                List.of("task result".repeat(400)),
                List.of("Architecture review"),
                List.of("Production readiness"));
        assertThat(evaluation.passed()).isTrue();
    }

    @Test
    void detectsShortAnswerOrConflictingCompletionKind() {
        StandardMissionQualityGate gate = new StandardMissionQualityGate();
        var candidate = new StandardMissionQualityGate.Candidate(
                "pa.mission-final-result/v2",
                "Summary",
                "Too short",
                "COMPLETE",
                List.of(),
                List.of("Failed step"),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        StandardMissionQualityGate.Result evaluation = gate.evaluate(candidate, List.of(), List.of());
        assertThat(evaluation.passed()).isFalse();
        assertThat(evaluation.failureCodes())
                .contains("STANDARD_ANSWER_TOO_SHORT", "STANDARD_COMPLETION_KIND_CONTRADICTS_FAILED_ITEMS");
    }

    @Test
    void requiresV2TaskAndAcceptanceCoverage() {
        StandardMissionQualityGate gate = new StandardMissionQualityGate();
        var candidate = new StandardMissionQualityGate.Candidate(
                "pa.mission-final-result/v2",
                "Summary",
                "A".repeat(400),
                "COMPLETE",
                List.of("Different item"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        StandardMissionQualityGate.Result evaluation = gate.evaluate(
                candidate, List.of("settled result"), List.of("Architecture review"), List.of("Production readiness"));

        assertThat(evaluation.failureCodes())
                .contains("STANDARD_TASK_COVERAGE_MISSING", "STANDARD_ACCEPTANCE_COVERAGE_MISSING");
    }

    @Test
    void passesWithTaskAndAcceptanceOutcomesWithoutVerbatimCopyInNarrative() {
        StandardMissionQualityGate gate = new StandardMissionQualityGate();
        String section = "### 系统架构综合评估报告\n\n本报告从多角度深入探讨了系统的整体性能表现、容灾演练方案、跨可用区部署细节与运维规程。"
                + "通过对底层存储引擎与网络拓扑的全面排查，确认高可用机制符合预期，同时给出了上线前的核心参数调优矩阵与演化路径规划。"
                + "各子系统之间的交互模式得到优化，在保障稳定性的同时满足了业务扩展需求。\n\n";

        var candidate = new StandardMissionQualityGate.Candidate(
                "pa.mission-final-result/v2",
                "Summary",
                section.repeat(4),
                "COMPLETE",
                List.of(),
                List.of(),
                List.of(new StandardMissionQualityGate.TaskOutcome("task-arch-eval", "COMPLETED")),
                List.of(new StandardMissionQualityGate.AcceptanceOutcome(0, "SATISFIED", List.of("task-arch-eval"))),
                List.of(new SourceReference("src-001", "Ethereum Docs", "https://ethereum.org/en/developers/docs/")),
                List.of("https://ethereum.org/en/developers/docs/"));

        StandardMissionQualityGate.Result evaluation = gate.evaluate(
                candidate,
                List.of("settled result".repeat(20)),
                List.of("task-arch-eval"),
                List.of("评估系统架构与微服务划分"),
                List.of("生产可用性审查与安全审计"),
                Instant.parse("2026-09-05T00:00:00Z"));

        assertThat(evaluation.passed()).isTrue();
    }

    @Test
    void detectsTemporalInconsistencyWithAsOf() {
        StandardMissionQualityGate gate = new StandardMissionQualityGate();
        String section = "### 调查分析报告\n\n注意：研究窗口包含可能晚于研究执行时点的日期，因此部分事实可能未经过充分观察与最终验证。"
                + "这篇分析覆盖了区块链协议在各个维度的最新发展和演进趋势，提供各方面的参考资料。\n\n";

        var candidate = new StandardMissionQualityGate.Candidate(
                "pa.mission-final-result/v2",
                "Summary",
                section.repeat(4),
                "COMPLETE",
                List.of(),
                List.of(),
                List.of(new StandardMissionQualityGate.TaskOutcome("t-1", "COMPLETED")),
                List.of(),
                List.of(),
                List.of());

        StandardMissionQualityGate.Result evaluation = gate.evaluate(
                candidate,
                List.of("settled".repeat(20)),
                List.of("t-1"),
                List.of("协议追踪"),
                List.of(),
                Instant.parse("2026-09-05T00:00:00Z"));

        assertThat(evaluation.passed()).isFalse();
        assertThat(evaluation.failureCodes()).contains("STANDARD_TEMPORAL_CONTRADICTION");
    }

    @Test
    void detectsInvalidTaskOutcome() {
        StandardMissionQualityGate gate = new StandardMissionQualityGate();
        var candidate = new StandardMissionQualityGate.Candidate(
                "pa.mission-final-result/v2",
                "Summary",
                "Valid long answer markdown content with sufficient length for standard quality gate. ".repeat(10),
                "COMPLETE",
                List.of(),
                List.of(),
                List.of(new StandardMissionQualityGate.TaskOutcome("unknown-task", "COMPLETED")),
                List.of(),
                List.of(),
                List.of());

        StandardMissionQualityGate.Result evaluation = gate.evaluate(
                candidate, List.of("settled"), List.of("real-task"), List.of("Objective"), List.of(), null);

        assertThat(evaluation.passed()).isFalse();
        assertThat(evaluation.failureCodes())
                .contains("STANDARD_TASK_OUTCOME_UNKNOWN_TASK", "STANDARD_TASK_COVERAGE_MISSING");
    }

    @Test
    void rejectsDuplicateTaskOutcomeOrInvalidStatus() {
        StandardMissionQualityGate gate = new StandardMissionQualityGate();
        var candidate = new StandardMissionQualityGate.Candidate(
                "pa.mission-final-result/v2",
                "Summary",
                "Valid long answer markdown content with sufficient length for standard quality gate. ".repeat(10),
                "COMPLETE",
                List.of(),
                List.of(),
                List.of(
                        new StandardMissionQualityGate.TaskOutcome("task-1", "COMPLETED"),
                        new StandardMissionQualityGate.TaskOutcome("task-1", "UNKNOWN_STATUS")),
                List.of(),
                List.of(),
                List.of());

        StandardMissionQualityGate.Result evaluation =
                gate.evaluate(candidate, List.of("settled"), List.of("task-1"), List.of("Objective"), List.of(), null);

        assertThat(evaluation.passed()).isFalse();
        assertThat(evaluation.failureCodes())
                .contains("STANDARD_TASK_OUTCOME_DUPLICATE", "STANDARD_TASK_OUTCOME_STATUS_INVALID");
    }

    @Test
    void rejectsAcceptanceOutcomeWithoutSupportingTasksOrReferencingUnknownTasks() {
        StandardMissionQualityGate gate = new StandardMissionQualityGate();
        var candidate = new StandardMissionQualityGate.Candidate(
                "pa.mission-final-result/v2",
                "Summary",
                "Valid long answer markdown content with sufficient length for standard quality gate. ".repeat(10),
                "COMPLETE",
                List.of(),
                List.of(),
                List.of(new StandardMissionQualityGate.TaskOutcome("task-1", "COMPLETED")),
                List.of(
                        new StandardMissionQualityGate.AcceptanceOutcome(0, "SATISFIED", List.of()),
                        new StandardMissionQualityGate.AcceptanceOutcome(1, "SATISFIED", List.of("ghost-task")),
                        new StandardMissionQualityGate.AcceptanceOutcome(5, "SATISFIED", List.of("task-1"))),
                List.of(),
                List.of());

        StandardMissionQualityGate.Result evaluation = gate.evaluate(
                candidate,
                List.of("settled"),
                List.of("task-1"),
                List.of("Objective"),
                List.of("Criterion 0", "Criterion 1"),
                null);

        assertThat(evaluation.passed()).isFalse();
        assertThat(evaluation.failureCodes())
                .contains(
                        "STANDARD_ACCEPTANCE_SUPPORTING_TASKS_MISSING",
                        "STANDARD_ACCEPTANCE_UNKNOWN_TASK",
                        "STANDARD_ACCEPTANCE_INDEX_OUT_OF_BOUNDS");
    }

    @Test
    void rejectsInvalidSectionSource() {
        StandardMissionQualityGate gate = new StandardMissionQualityGate();
        var candidate = new StandardMissionQualityGate.Candidate(
                "pa.mission-final-result/v2",
                "Summary",
                "Valid long answer markdown content with sufficient length for standard quality gate. ".repeat(10),
                "COMPLETE",
                List.of(),
                List.of(),
                List.of(new StandardMissionQualityGate.TaskOutcome("task-1", "COMPLETED")),
                List.of(new StandardMissionQualityGate.AcceptanceOutcome(0, "SATISFIED", List.of("task-1"))),
                List.of(new StandardMissionQualityGate.SectionSource("", List.of("src-001"))),
                List.of(),
                List.of());

        StandardMissionQualityGate.Result evaluation = gate.evaluate(
                candidate, List.of("settled"), List.of("task-1"), List.of("Objective"), List.of("Criterion 0"), null);

        assertThat(evaluation.passed()).isFalse();
        assertThat(evaluation.failureCodes()).contains("STANDARD_SECTION_SOURCE_INVALID");
    }
}

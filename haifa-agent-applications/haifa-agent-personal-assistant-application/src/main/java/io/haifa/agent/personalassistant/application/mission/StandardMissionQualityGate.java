package io.haifa.agent.personalassistant.application.mission;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic quality gate for Standard Mission synthesis results.
 *
 * <p>Ensures that the synthesized answer provides substantive narrative content, that completion
 * statuses are internally consistent, and that cited source references are well-formed.
 *
 * <p>Pure Java domain quality gate: does not depend on JSON serialization frameworks.
 */
public final class StandardMissionQualityGate {
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s<>\"'\\]\\[)]+");

    public record TaskOutcome(String taskId, String status) {
        public TaskOutcome {
            taskId = taskId == null ? "" : taskId.trim();
            status = status == null ? "" : status.trim();
        }
    }

    public record AcceptanceOutcome(int criterionIndex, String status, List<String> taskIds) {
        public AcceptanceOutcome {
            status = status == null ? "" : status.trim();
            taskIds = taskIds == null ? List.of() : List.copyOf(taskIds);
        }

        public AcceptanceOutcome(int criterionIndex, String status) {
            this(criterionIndex, status, List.of());
        }
    }

    public record SectionSource(String sectionHeading, List<String> sourceIds) {
        public SectionSource {
            sectionHeading = sectionHeading == null ? "" : sectionHeading.trim();
            sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
        }
    }

    public record Candidate(
            String schemaVersion,
            String directAnswer,
            String answerMarkdown,
            String completionKind,
            List<String> completedItems,
            List<String> failedItems,
            List<TaskOutcome> taskOutcomes,
            List<AcceptanceOutcome> acceptanceOutcomes,
            List<SectionSource> sectionSources,
            List<SourceReference> sources,
            List<String> sourceRefs) {
        public Candidate {
            schemaVersion = schemaVersion == null ? "" : schemaVersion.trim();
            directAnswer = directAnswer == null ? "" : directAnswer.trim();
            answerMarkdown = answerMarkdown == null ? "" : answerMarkdown.trim();
            completionKind = completionKind == null ? "" : completionKind.trim();
            completedItems = completedItems == null ? List.of() : List.copyOf(completedItems);
            failedItems = failedItems == null ? List.of() : List.copyOf(failedItems);
            taskOutcomes = taskOutcomes == null ? List.of() : List.copyOf(taskOutcomes);
            acceptanceOutcomes = acceptanceOutcomes == null ? List.of() : List.copyOf(acceptanceOutcomes);
            sectionSources = sectionSources == null ? List.of() : List.copyOf(sectionSources);
            sources = sources == null ? List.of() : List.copyOf(sources);
            sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        }

        public Candidate(
                String schemaVersion,
                String directAnswer,
                String answerMarkdown,
                String completionKind,
                List<String> completedItems,
                List<String> failedItems,
                List<TaskOutcome> taskOutcomes,
                List<AcceptanceOutcome> acceptanceOutcomes,
                List<SourceReference> sources,
                List<String> sourceRefs) {
            this(
                    schemaVersion,
                    directAnswer,
                    answerMarkdown,
                    completionKind,
                    completedItems,
                    failedItems,
                    taskOutcomes,
                    acceptanceOutcomes,
                    List.of(),
                    sources,
                    sourceRefs);
        }
    }

    public record Result(boolean passed, List<Failure> failures) {
        public static Result passedResult() {
            return new Result(true, List.of());
        }

        public List<String> failureCodes() {
            return failures.stream().map(Failure::code).toList();
        }

        public String revisionFeedback() {
            return failures.stream()
                    .map(f -> f.code() + (f.details().isEmpty() ? "" : ": " + String.join(", ", f.details())))
                    .collect(java.util.stream.Collectors.joining("; "));
        }
    }

    public record Failure(String code, List<String> details) {}

    public Result evaluate(Candidate candidate, List<String> taskResults) {
        return evaluate(candidate, taskResults, List.of(), List.of(), List.of(), null);
    }

    public Result evaluate(Candidate candidate, List<String> completedTaskObjectives, List<String> acceptanceCriteria) {
        return evaluate(candidate, List.of(), List.of(), completedTaskObjectives, acceptanceCriteria, null);
    }

    public Result evaluate(
            Candidate candidate,
            List<String> settledTaskResults,
            List<String> completedTaskObjectives,
            List<String> acceptanceCriteria) {
        return evaluate(candidate, settledTaskResults, List.of(), completedTaskObjectives, acceptanceCriteria, null);
    }

    public Result evaluate(
            Candidate candidate,
            List<String> settledTaskResults,
            List<String> completedTaskIds,
            List<String> completedTaskObjectives,
            List<String> acceptanceCriteria) {
        return evaluate(
                candidate, settledTaskResults, completedTaskIds, completedTaskObjectives, acceptanceCriteria, null);
    }

    public Result evaluate(
            Candidate candidate,
            List<String> settledTaskResults,
            List<String> completedTaskIds,
            List<String> completedTaskObjectives,
            List<String> acceptanceCriteria,
            Instant asOf) {
        if (candidate == null) {
            return new Result(false, List.of(new Failure("STANDARD_RESULT_EMPTY", List.of())));
        }
        List<Failure> failures = new ArrayList<>();
        boolean versionTwo = "pa.mission-final-result/v2".equals(candidate.schemaVersion());

        String answer = versionTwo && !candidate.answerMarkdown().isBlank()
                ? candidate.answerMarkdown()
                : candidate.directAnswer();

        if (versionTwo && candidate.answerMarkdown().isBlank()) {
            failures.add(new Failure("STANDARD_ANSWER_MARKDOWN_MISSING", List.of()));
        }
        int settledCharacters =
                settledTaskResults.stream().mapToInt(String::length).sum();
        int minimumAnswerCharacters = versionTwo ? Math.min(6_000, Math.max(300, settledCharacters / 20)) : 30;
        if (answer.length() < minimumAnswerCharacters) {
            failures.add(new Failure(
                    "STANDARD_ANSWER_TOO_SHORT",
                    List.of("Answer content is too short: " + answer.length() + "/" + minimumAnswerCharacters
                            + " chars")));
        }

        boolean hasTaskOutcomes = !candidate.taskOutcomes().isEmpty();
        boolean hasAcceptanceOutcomes = !candidate.acceptanceOutcomes().isEmpty();

        boolean anyTaskFailed = false;
        boolean anyCriterionUnsatisfied = false;
        Set<String> authoritativeTaskIdSet = new LinkedHashSet<>(completedTaskIds);

        if (versionTwo) {
            if (hasTaskOutcomes) {
                Set<String> seenTaskIds = new LinkedHashSet<>();
                List<String> duplicateTaskIds = new ArrayList<>();
                List<String> invalidStatusTasks = new ArrayList<>();
                List<String> unknownTaskIds = new ArrayList<>();
                Set<String> reportedCompletedTaskIds = new LinkedHashSet<>();

                for (TaskOutcome outcome : candidate.taskOutcomes()) {
                    String taskId = outcome.taskId();
                    String status = outcome.status();
                    if (!seenTaskIds.add(taskId)) {
                        duplicateTaskIds.add(taskId);
                    }
                    if (!"COMPLETED".equals(status) && !"FAILED".equals(status)) {
                        invalidStatusTasks.add(taskId + ":" + status);
                    }
                    if (!authoritativeTaskIdSet.isEmpty() && !authoritativeTaskIdSet.contains(taskId)) {
                        unknownTaskIds.add(taskId);
                    }
                    if ("COMPLETED".equals(status)) {
                        reportedCompletedTaskIds.add(taskId);
                    } else {
                        anyTaskFailed = true;
                    }
                }
                if (!duplicateTaskIds.isEmpty()) {
                    failures.add(new Failure("STANDARD_TASK_OUTCOME_DUPLICATE", duplicateTaskIds));
                }
                if (!invalidStatusTasks.isEmpty()) {
                    failures.add(new Failure("STANDARD_TASK_OUTCOME_STATUS_INVALID", invalidStatusTasks));
                }
                if (!unknownTaskIds.isEmpty()) {
                    failures.add(new Failure("STANDARD_TASK_OUTCOME_UNKNOWN_TASK", unknownTaskIds));
                }
                List<String> missingTaskIds = completedTaskIds.stream()
                        .filter(id -> !reportedCompletedTaskIds.contains(id.trim()))
                        .toList();
                if (!missingTaskIds.isEmpty()) {
                    failures.add(new Failure("STANDARD_TASK_COVERAGE_MISSING", missingTaskIds));
                }
            } else {
                Set<String> completedItems = new LinkedHashSet<>(candidate.completedItems());
                List<String> missingObjectives = completedTaskObjectives.stream()
                        .filter(value -> !completedItems.contains(value.trim()))
                        .toList();
                if (!missingObjectives.isEmpty()) {
                    failures.add(new Failure("STANDARD_TASK_COVERAGE_MISSING", missingObjectives));
                }
            }

            if (hasAcceptanceOutcomes) {
                Set<Integer> seenIndices = new LinkedHashSet<>();
                List<String> duplicateIndices = new ArrayList<>();
                List<String> outOfBoundsIndices = new ArrayList<>();
                List<String> invalidStatusCriteria = new ArrayList<>();
                List<String> missingSupportTasks = new ArrayList<>();
                List<String> unknownSupportTasks = new ArrayList<>();
                Set<Integer> allIndices = new LinkedHashSet<>();

                for (AcceptanceOutcome outcome : candidate.acceptanceOutcomes()) {
                    int index = outcome.criterionIndex();
                    String status = outcome.status();
                    if (index < 0 || index >= acceptanceCriteria.size()) {
                        outOfBoundsIndices.add("index-" + index);
                    } else if (!seenIndices.add(index)) {
                        duplicateIndices.add("index-" + index);
                    }
                    if (!"SATISFIED".equals(status) && !"UNSATISFIED".equals(status)) {
                        invalidStatusCriteria.add("index-" + index + ":" + status);
                    }
                    if ("SATISFIED".equals(status)) {
                        if (outcome.taskIds().isEmpty()) {
                            missingSupportTasks.add("index-" + index);
                        }
                    } else {
                        anyCriterionUnsatisfied = true;
                    }
                    if (!authoritativeTaskIdSet.isEmpty()) {
                        for (String refTaskId : outcome.taskIds()) {
                            if (!authoritativeTaskIdSet.contains(refTaskId)) {
                                unknownSupportTasks.add("index-" + index + " references unknown task: " + refTaskId);
                            }
                        }
                    }
                    allIndices.add(index);
                }
                if (!outOfBoundsIndices.isEmpty()) {
                    failures.add(new Failure("STANDARD_ACCEPTANCE_INDEX_OUT_OF_BOUNDS", outOfBoundsIndices));
                }
                if (!duplicateIndices.isEmpty()) {
                    failures.add(new Failure("STANDARD_ACCEPTANCE_INDEX_DUPLICATE", duplicateIndices));
                }
                if (!invalidStatusCriteria.isEmpty()) {
                    failures.add(new Failure("STANDARD_ACCEPTANCE_STATUS_INVALID", invalidStatusCriteria));
                }
                if (!missingSupportTasks.isEmpty()) {
                    failures.add(new Failure("STANDARD_ACCEPTANCE_SUPPORTING_TASKS_MISSING", missingSupportTasks));
                }
                if (!unknownSupportTasks.isEmpty()) {
                    failures.add(new Failure("STANDARD_ACCEPTANCE_UNKNOWN_TASK", unknownSupportTasks));
                }
                List<String> missingIndices = new ArrayList<>();
                for (int i = 0; i < acceptanceCriteria.size(); i++) {
                    if (!allIndices.contains(i)) {
                        missingIndices.add("criterion-" + i + ": " + acceptanceCriteria.get(i));
                    }
                }
                if (!missingIndices.isEmpty()) {
                    failures.add(new Failure("STANDARD_ACCEPTANCE_COVERAGE_MISSING", missingIndices));
                }
            } else {
                Set<String> completedItems = new LinkedHashSet<>(candidate.completedItems());
                Set<String> failedItems = new LinkedHashSet<>(candidate.failedItems());
                List<String> missingCriteria = acceptanceCriteria.stream()
                        .filter(value -> !completedItems.contains(value.trim()) && !failedItems.contains(value.trim()))
                        .toList();
                if (!missingCriteria.isEmpty()) {
                    failures.add(new Failure("STANDARD_ACCEPTANCE_COVERAGE_MISSING", missingCriteria));
                }
            }
        }

        for (SectionSource sec : candidate.sectionSources()) {
            if (sec.sectionHeading().isBlank()) {
                failures.add(new Failure("STANDARD_SECTION_SOURCE_INVALID", List.of("sectionHeading is blank")));
            }
            if (sec.sourceIds().isEmpty()) {
                failures.add(new Failure(
                        "STANDARD_SECTION_SOURCE_INVALID",
                        List.of("sourceIds is empty for section: " + sec.sectionHeading())));
            }
        }

        String completionKind = candidate.completionKind();
        boolean hasExplicitFailedItems = !candidate.failedItems().isEmpty();
        if ("COMPLETE".equals(completionKind)) {
            if (hasExplicitFailedItems || anyTaskFailed || anyCriterionUnsatisfied) {
                failures.add(new Failure(
                        "STANDARD_COMPLETION_KIND_CONTRADICTS_FAILED_ITEMS",
                        List.of("COMPLETE status cannot have non-empty failedItems or unsatisfied outcomes")));
            }
        }
        if ("PARTIAL".equals(completionKind)) {
            if (!hasExplicitFailedItems && !anyTaskFailed && !anyCriterionUnsatisfied) {
                failures.add(new Failure(
                        "STANDARD_COMPLETION_KIND_CONTRADICTS_FAILED_ITEMS",
                        List.of("PARTIAL status must specify failedItems or unsatisfied outcomes")));
            }
        }

        if (asOf != null) {
            if (answer.contains("晚于研究执行时点") || answer.contains("晚于执行时点")) {
                failures.add(
                        new Failure(
                                "STANDARD_TEMPORAL_CONTRADICTION",
                                List.of(
                                        "Answer contains contradictory defensive statement claiming dates are after execution point")));
            }
        }

        for (SourceReference source : candidate.sources()) {
            String locator = source.locator();
            if (!URL_PATTERN.matcher(locator).matches()) {
                failures.add(new Failure("STANDARD_SOURCE_REF_INVALID", List.of(locator)));
            }
        }

        for (String ref : candidate.sourceRefs()) {
            if (ref != null && (ref.startsWith("http://") || ref.startsWith("https://"))) {
                if (!URL_PATTERN.matcher(ref).matches()) {
                    failures.add(new Failure("STANDARD_SOURCE_REF_INVALID", List.of(ref)));
                }
            }
        }

        return new Result(failures.isEmpty(), List.copyOf(failures));
    }
}

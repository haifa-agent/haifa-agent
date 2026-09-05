package io.haifa.agent.personalassistant.application.mission;

import com.fasterxml.jackson.databind.JsonNode;
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
 */
public final class StandardMissionQualityGate {
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s<>\"'\\]\\[)]+");

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

    public Result evaluate(JsonNode finalResult, List<String> taskResults) {
        return evaluate(finalResult, taskResults, List.of(), List.of());
    }

    public Result evaluate(
            JsonNode finalResult, List<String> completedTaskObjectives, List<String> acceptanceCriteria) {
        return evaluate(finalResult, List.of(), completedTaskObjectives, acceptanceCriteria);
    }

    public Result evaluate(
            JsonNode finalResult,
            List<String> settledTaskResults,
            List<String> completedTaskObjectives,
            List<String> acceptanceCriteria) {
        if (finalResult == null || !finalResult.isObject()) {
            return new Result(false, List.of(new Failure("STANDARD_RESULT_EMPTY", List.of())));
        }
        List<Failure> failures = new ArrayList<>();
        boolean versionTwo = "pa.mission-final-result/v2"
                .equals(finalResult.path("schemaVersion").asText());
        String answer = finalResult.path("answerMarkdown").isTextual()
                ? finalResult.path("answerMarkdown").asText().trim()
                : finalResult.path("directAnswer").asText("").trim();

        if (versionTwo && !finalResult.path("answerMarkdown").isTextual()) {
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

        if (versionTwo) {
            Set<String> completedItems = textValues(finalResult.path("completedItems"));
            Set<String> failedItems = textValues(finalResult.path("failedItems"));
            List<String> missingObjectives = completedTaskObjectives.stream()
                    .filter(value -> !completedItems.contains(value.trim()) || !answer.contains(value.trim()))
                    .toList();
            if (!missingObjectives.isEmpty()) {
                failures.add(new Failure("STANDARD_TASK_COVERAGE_MISSING", missingObjectives));
            }
            List<String> missingCriteria = acceptanceCriteria.stream()
                    .filter(value -> (!completedItems.contains(value.trim()) && !failedItems.contains(value.trim()))
                            || !answer.contains(value.trim()))
                    .toList();
            if (!missingCriteria.isEmpty()) {
                failures.add(new Failure("STANDARD_ACCEPTANCE_COVERAGE_MISSING", missingCriteria));
            }
        }

        String completionKind = finalResult.path("completionKind").asText();
        JsonNode failedItems = finalResult.path("failedItems");
        if ("COMPLETE".equals(completionKind) && failedItems.isArray() && !failedItems.isEmpty()) {
            failures.add(new Failure(
                    "STANDARD_COMPLETION_KIND_CONTRADICTS_FAILED_ITEMS",
                    List.of("COMPLETE status cannot have non-empty failedItems")));
        }
        if ("PARTIAL".equals(completionKind) && failedItems.isArray() && failedItems.isEmpty()) {
            failures.add(new Failure(
                    "STANDARD_COMPLETION_KIND_CONTRADICTS_FAILED_ITEMS",
                    List.of("PARTIAL status must specify failedItems")));
        }

        JsonNode sourceRefs = finalResult.path("sourceRefs");
        if (sourceRefs.isArray()) {
            for (JsonNode ref : sourceRefs) {
                String url = ref.asText();
                if ((url.startsWith("http://") || url.startsWith("https://"))
                        && !URL_PATTERN.matcher(url).matches()) {
                    failures.add(new Failure("STANDARD_SOURCE_REF_INVALID", List.of(url)));
                }
            }
        }

        return new Result(failures.isEmpty(), List.copyOf(failures));
    }

    private static Set<String> textValues(JsonNode values) {
        if (!values.isArray()) return Set.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        values.forEach(value -> {
            if (value.isTextual() && !value.asText().isBlank())
                result.add(value.asText().trim());
        });
        return Set.copyOf(result);
    }
}

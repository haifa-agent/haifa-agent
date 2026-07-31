package io.haifa.agent.testing.delivery;

import io.haifa.agent.testing.repository.RepositoryRevision;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds the versioned phase summary without changing native Gate or budget semantics. */
final class AutonomousDeliveryGateResultAggregator {
    private AutonomousDeliveryGateResultAggregator() {}

    static Aggregation aggregate(
            AutonomousDeliverySuiteManifest suite,
            AutonomousDeliveryMatrixManifest.Combination matrixCombination,
            String buildCommit,
            RepositoryRevision productRevision,
            RepositoryRevision testConfigRevision,
            RepositoryRevision productRevisionAfter,
            RepositoryRevision testConfigRevisionAfter,
            Instant finishedAt,
            boolean prerequisiteGatesPassed,
            Map<String, Object> deterministicAnalyze,
            Map<String, Object> deterministicReplay,
            List<Map<String, Object>> results) {
        Objects.requireNonNull(suite, "suite must not be null");
        Objects.requireNonNull(matrixCombination, "matrixCombination must not be null");
        Objects.requireNonNull(finishedAt, "finishedAt must not be null");
        Objects.requireNonNull(deterministicAnalyze, "deterministicAnalyze must not be null");
        Objects.requireNonNull(deterministicReplay, "deterministicReplay must not be null");
        results = List.copyOf(Objects.requireNonNull(results, "results must not be null"));

        int executionCalls = results.stream()
                .mapToInt(result -> ((Number) result.get("executionCalls")).intValue())
                .sum();
        int scratchProvisioned = results.stream()
                .mapToInt(result -> ((Number) result.get("scratchProvisionedCount")).intValue())
                .sum();
        boolean scratchExercised = executionCalls > 0 && executionCalls == scratchProvisioned;
        boolean repositoryStateStable =
                productRevision.equals(productRevisionAfter) && testConfigRevision.equals(testConfigRevisionAfter);
        boolean successful = prerequisiteGatesPassed && scratchExercised && repositoryStateStable;

        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("schemaVersion", 3);
        summary.put("phase", suite.phase());
        summary.put("suiteId", suite.suiteId());
        summary.put("matrixRef", suite.matrixRef());
        summary.put("matrixCombination", matrixCombination);
        summary.put("buildCommit", buildCommit);
        summary.put("productRevision", productRevision);
        summary.put("testConfigRevision", testConfigRevision);
        summary.put("productRevisionAfter", productRevisionAfter);
        summary.put("testConfigRevisionAfter", testConfigRevisionAfter);
        summary.put("repositoryStateStable", repositoryStateStable);
        summary.put("finishedAt", finishedAt.toString());
        summary.put("successful", successful);
        summary.put("executionCalls", executionCalls);
        summary.put("scratchProvisionedCount", scratchProvisioned);
        summary.put("scratchExercised", scratchExercised);
        summary.put("deterministicReadOnlyAnalyzeStub", deterministicAnalyze);
        summary.put("deterministicTraceReplay", deterministicReplay);
        summary.put("results", results);
        if ("PHASE_3".equals(suite.phase())) {
            summary.put("capabilityMatrix", capabilityMatrix(results));
            summary.put("metrics", phaseThreeMetrics(results));
        }
        return new Aggregation(summary, successful);
    }

    private static Map<String, Object> capabilityMatrix(List<Map<String, Object>> results) {
        return Map.of(
                "schemaVersion",
                1,
                "languages",
                distinct(results, "language"),
                "taskTypes",
                distinct(results, "taskType"),
                "capabilities",
                flattened(results, "capabilities"),
                "riskDimensions",
                flattened(results, "riskDimensions"));
    }

    private static Map<String, Object> phaseThreeMetrics(List<Map<String, Object>> results) {
        long passed = results.stream()
                .filter(value -> Boolean.TRUE.equals(value.get("gatePassed")))
                .count();
        long acceptance = results.stream()
                .filter(value -> Boolean.TRUE.equals(value.get("acceptancePassed")))
                .count();
        long zeroChange = results.stream()
                .filter(value -> !Boolean.TRUE.equals(value.get("workspaceChanged")))
                .count();
        return Map.of(
                "runCount",
                results.size(),
                "autonomousCompletionRate",
                rate(passed, results.size()),
                "hiddenAcceptancePassRate",
                rate(acceptance, results.size()),
                "zeroWorkspaceChangeRate",
                rate(zeroChange, results.size()),
                "modelCalls",
                sum(results, "modelCalls"),
                "toolCalls",
                sum(results, "toolCalls"),
                "toolFailures",
                sum(results, "toolFailures"),
                "inputTokens",
                sum(results, "inputTokens"),
                "outputTokens",
                sum(results, "outputTokens"),
                "costKnown",
                false);
    }

    private static List<String> distinct(List<Map<String, Object>> results, String key) {
        return results.stream()
                .map(value -> String.valueOf(value.get(key)))
                .distinct()
                .sorted()
                .toList();
    }

    private static List<String> flattened(List<Map<String, Object>> results, String key) {
        return results.stream()
                .flatMap(value -> ((List<?>) value.get(key)).stream())
                .map(String::valueOf)
                .distinct()
                .sorted()
                .toList();
    }

    private static long sum(List<Map<String, Object>> results, String key) {
        return results.stream()
                .mapToLong(value -> ((Number) value.get(key)).longValue())
                .sum();
    }

    private static double rate(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : Math.round(numerator * 10_000.0 / denominator) / 100.0;
    }

    record Aggregation(Map<String, Object> summary, boolean successful) {
        Aggregation {
            summary = Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(summary, "summary must not be null")));
        }
    }
}

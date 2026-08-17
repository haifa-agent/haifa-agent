package io.haifa.agent.testing.delivery;

import io.haifa.agent.testing.harness.PlatformManifest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds the versioned phase summary without changing native Gate or budget semantics. */
final class AutonomousDeliveryGateResultAggregator {
    private AutonomousDeliveryGateResultAggregator() {}

    static NativeResult aggregate(
            AutonomousDeliverySuiteManifest suite,
            PlatformManifest.PlatformProfile matrixCombination,
            String buildCommit,
            boolean prerequisiteGatesPassed,
            Map<String, Object> deterministicAnalyze,
            Map<String, Object> deterministicReplay,
            List<Map<String, Object>> results,
            AutonomousDeliveryLiveBudget.Evidence liveBudgetEvidence) {
        Objects.requireNonNull(suite, "suite must not be null");
        Objects.requireNonNull(matrixCombination, "matrixCombination must not be null");
        Objects.requireNonNull(deterministicAnalyze, "deterministicAnalyze must not be null");
        Objects.requireNonNull(deterministicReplay, "deterministicReplay must not be null");
        Objects.requireNonNull(liveBudgetEvidence, "liveBudgetEvidence must not be null");
        results = List.copyOf(Objects.requireNonNull(results, "results must not be null"));

        int executionCalls = results.stream()
                .mapToInt(result -> ((Number) result.get("executionCalls")).intValue())
                .sum();
        int scratchProvisioned = results.stream()
                .mapToInt(result -> ((Number) result.get("scratchProvisionedCount")).intValue())
                .sum();
        boolean scratchExercised = executionCalls > 0 && executionCalls == scratchProvisioned;
        boolean successful = prerequisiteGatesPassed && scratchExercised && liveBudgetEvidence.passed();

        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("schemaVersion", 4);
        summary.put("phase", suite.phase());
        summary.put("suiteId", suite.suiteId());
        summary.put("matrixRef", suite.matrixRef());
        summary.put("matrixCombination", matrixCombination);
        summary.put("buildCommit", buildCommit);
        summary.put("gatePassed", successful);
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
        return new NativeResult(
                successful ? "GATE_PASSED" : "GATE_FAILED",
                successful ? "NONE" : "ACCEPTANCE_FAILED",
                successful,
                summary);
    }

    private static Map<String, Object> capabilityMatrix(List<Map<String, Object>> results) {
        LinkedHashMap<String, Object> matrix = new LinkedHashMap<>();
        matrix.put("schemaVersion", 1);
        matrix.put(
                "nativeStatus",
                results.stream().allMatch(value -> "GATE_PASSED".equals(value.get("nativeStatus")))
                        ? "GATE_PASSED"
                        : "GATE_FAILED");
        matrix.put("languages", distinct(results, "language"));
        matrix.put("taskTypes", distinct(results, "taskType"));
        matrix.put("capabilities", flattened(results, "capabilities"));
        matrix.put("riskDimensions", flattened(results, "riskDimensions"));
        return Map.copyOf(matrix);
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

    record NativeResult(
            String nativeStatus, String failureClassification, boolean successful, Map<String, Object> artifact) {
        NativeResult {
            Objects.requireNonNull(nativeStatus, "nativeStatus must not be null");
            Objects.requireNonNull(failureClassification, "failureClassification must not be null");
            artifact = Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(artifact, "artifact must not be null")));
        }
    }
}

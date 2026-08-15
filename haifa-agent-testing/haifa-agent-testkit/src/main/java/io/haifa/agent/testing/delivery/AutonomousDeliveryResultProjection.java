package io.haifa.agent.testing.delivery;

import io.haifa.agent.testing.repository.RepositoryRevision;
import io.haifa.agent.testing.result.TestResultProjection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Maps native Autonomous Delivery repeat summaries without changing gatePassed semantics. */
final class AutonomousDeliveryResultProjection {
    private static final String SUITE_SYSTEM = "AUTONOMOUS_DELIVERY";

    private AutonomousDeliveryResultProjection() {}

    static TestResultProjection.Batch batch(
            AutonomousDeliverySuiteManifest suite,
            AutonomousDeliveryMatrixManifest.Combination combination,
            RepositoryRevision productRevision,
            RepositoryRevision testConfigRevision,
            Instant generatedAt,
            List<Map<String, Object>> nativeResults) {
        List<TestResultProjection> projections = nativeResults.stream()
                .map(result -> project(suite.suiteId(), combination, productRevision, testConfigRevision, result))
                .toList();
        return new TestResultProjection.Batch(
                1, "CROSS_SUITE_RESULT_V1", SUITE_SYSTEM, suite.suiteId(), generatedAt.toString(), projections);
    }

    private static TestResultProjection project(
            String suiteId,
            AutonomousDeliveryMatrixManifest.Combination combination,
            RepositoryRevision productRevision,
            RepositoryRevision testConfigRevision,
            Map<String, Object> result) {
        boolean passed = Boolean.TRUE.equals(result.get("gatePassed"));
        String nativeStatus = String.valueOf(
                java.util.Objects.requireNonNull(result.get("nativeStatus"), "native status is required"));
        int repetition = ((Number) result.get("repetition")).intValue();
        String caseId = String.valueOf(result.get("caseId"));
        return new TestResultProjection(
                1,
                SUITE_SYSTEM,
                suiteId,
                caseId,
                String.valueOf(result.get("caseVersion")),
                repetition,
                "E2E",
                "LIVE",
                "CODING_TERMINAL",
                combination.platform(),
                combination.id(),
                productRevision.commit(),
                testConfigRevision.commit(),
                passed ? TestResultProjection.Status.PASS : TestResultProjection.Status.FAIL,
                nativeStatus,
                null,
                Math.round(((Number) result.get("wallTimeSeconds")).doubleValue() * 1000.0),
                providerUsage(result),
                "case-" + caseId + "/repeat-%02d".formatted(repetition),
                failureCategory(result));
    }

    private static Map<String, Object> providerUsage(Map<String, Object> result) {
        LinkedHashMap<String, Object> usage = new LinkedHashMap<>();
        usage.put("modelCalls", number(result, "modelCalls"));
        usage.put("toolCalls", number(result, "toolCalls"));
        usage.put("toolFailures", number(result, "toolFailures"));
        usage.put("inputTokens", number(result, "inputTokens"));
        usage.put("outputTokens", number(result, "outputTokens"));
        usage.put("costKnown", false);
        return Map.copyOf(usage);
    }

    private static String failureCategory(Map<String, Object> result) {
        if (Boolean.TRUE.equals(result.get("gatePassed"))) {
            return "NONE";
        }
        if (!Boolean.TRUE.equals(result.get("clientContractPassed"))) {
            return "CODING_CLIENT_CONTRACT";
        }
        if (!Boolean.TRUE.equals(result.get("boundedConvergence"))) {
            return "BUDGET_OR_CONVERGENCE";
        }
        if (!Boolean.TRUE.equals(result.get("acceptancePassed"))) {
            return "HIDDEN_ACCEPTANCE";
        }
        if (!Boolean.TRUE.equals(result.get("verificationPassed"))) {
            return "PHASE_VERIFICATION";
        }
        return "GATE_POLICY";
    }

    private static long number(Map<String, Object> result, String key) {
        return ((Number) result.get(key)).longValue();
    }
}

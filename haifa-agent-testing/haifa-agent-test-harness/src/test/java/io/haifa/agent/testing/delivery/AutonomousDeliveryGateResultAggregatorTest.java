package io.haifa.agent.testing.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.haifa.agent.testing.harness.PlatformManifest;
import io.haifa.agent.testing.harness.ResolvedTestPlan;
import io.haifa.agent.testing.repository.RepositoryRevision;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AutonomousDeliveryGateResultAggregatorTest {
    private static final RepositoryRevision PRODUCT =
            new RepositoryRevision("1111111111111111111111111111111111111111", false);
    private static final RepositoryRevision CONFIG =
            new RepositoryRevision("2222222222222222222222222222222222222222", false);

    @Test
    void preservesPhaseThreeSummarySchemaAndDerivedMetrics() {
        List<Map<String, Object>> results = List.of(
                result("Java", "BUG_FIX", true, true, true, 1, 1, 2, 3, 0, 100, 20),
                result("Python", "FEATURE", false, false, false, 1, 1, 1, 2, 1, 80, 10));

        AutonomousDeliveryGateResultAggregator.Aggregation aggregation =
                AutonomousDeliveryGateResultAggregator.aggregate(
                        suite("PHASE_3"),
                        combination(),
                        PRODUCT.commit(),
                        PRODUCT,
                        CONFIG,
                        PRODUCT,
                        CONFIG,
                        Instant.parse("2026-07-31T08:00:00Z"),
                        true,
                        Map.of("required", false),
                        Map.of("required", true, "passed", true),
                        results,
                        executionPlan(),
                        liveBudget(true));

        assertTrue(aggregation.successful());
        assertEquals(
                List.of(
                        "schemaVersion",
                        "phase",
                        "suiteId",
                        "matrixRef",
                        "matrixCombination",
                        "buildCommit",
                        "productRevision",
                        "testConfigRevision",
                        "productRevisionAfter",
                        "testConfigRevisionAfter",
                        "repositoryStateStable",
                        "finishedAt",
                        "successful",
                        "executionCalls",
                        "scratchProvisionedCount",
                        "scratchExercised",
                        "deterministicReadOnlyAnalyzeStub",
                        "deterministicTraceReplay",
                        "executionPlanSha256",
                        "liveBudget",
                        "results",
                        "capabilityMatrix",
                        "metrics"),
                List.copyOf(aggregation.summary().keySet()));
        assertEquals(2, aggregation.summary().get("executionCalls"));
        Map<?, ?> metrics = (Map<?, ?>) aggregation.summary().get("metrics");
        assertEquals(50.0, metrics.get("autonomousCompletionRate"));
        assertEquals(5L, metrics.get("toolCalls"));
        Map<?, ?> capabilityMatrix = (Map<?, ?>) aggregation.summary().get("capabilityMatrix");
        assertEquals(List.of("Java", "Python"), capabilityMatrix.get("languages"));
        assertEquals("GATE_FAILED", capabilityMatrix.get("nativeStatus"));
    }

    @Test
    void failsSummaryWhenScratchOrRepositoryStabilityDoesNotConverge() {
        Map<String, Object> result = result("Java", "BUG_FIX", true, true, true, 2, 1, 1, 1, 0, 1, 1);

        AutonomousDeliveryGateResultAggregator.Aggregation aggregation =
                AutonomousDeliveryGateResultAggregator.aggregate(
                        suite("PHASE_1"),
                        combination(),
                        PRODUCT.commit(),
                        PRODUCT,
                        CONFIG,
                        new RepositoryRevision("3333333333333333333333333333333333333333", false),
                        CONFIG,
                        Instant.parse("2026-07-31T08:00:00Z"),
                        true,
                        Map.of("required", false),
                        Map.of("required", false),
                        List.of(result),
                        executionPlan(),
                        liveBudget(true));

        assertFalse(aggregation.successful());
        assertEquals(false, aggregation.summary().get("repositoryStateStable"));
        assertEquals(false, aggregation.summary().get("scratchExercised"));
        assertFalse(aggregation.summary().containsKey("metrics"));
    }

    private static AutonomousDeliverySuiteManifest suite(String phase) {
        return new AutonomousDeliverySuiteManifest(
                1,
                "suite-v1",
                AutonomousDeliveryCaseCatalog.EXPECTED_CATALOG_ID,
                phase,
                "autonomous-delivery-v1",
                "hidden-v1",
                "deterministic-read-only-analyze-v1",
                new AutonomousDeliverySuiteManifest.Budget(60_000, 10, 20, 5, 1),
                List.of(new AutonomousDeliverySuiteManifest.CaseSelection("01", 1, true)));
    }

    private static PlatformManifest.PlatformProfile combination() {
        return new PlatformManifest.PlatformProfile(
                "linux-primary",
                "linux",
                "unix-pty",
                "local-native",
                "deny",
                "auto",
                "LOCAL_NATIVE",
                "posix-local-native-v1",
                1);
    }

    private static ResolvedTestPlan executionPlan() {
        return new ResolvedTestPlan(1, Map.of("schemaVersion", 1), "a".repeat(64));
    }

    private static AutonomousDeliveryLiveBudget.Evidence liveBudget(boolean passed) {
        return new AutonomousDeliveryLiveBudget.Evidence("CNY", 1000, 1000, 600, 1, 100, 20, 500, 60_000, passed);
    }

    private static Map<String, Object> result(
            String language,
            String taskType,
            boolean gatePassed,
            boolean acceptancePassed,
            boolean workspaceChanged,
            int executionCalls,
            int scratchProvisioned,
            long modelCalls,
            long toolCalls,
            long toolFailures,
            long inputTokens,
            long outputTokens) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("language", language);
        result.put("taskType", taskType);
        result.put("capabilities", List.of("EDIT", "VERIFY"));
        result.put("riskDimensions", List.of("FAILURE_ATOMICITY"));
        result.put("gatePassed", gatePassed);
        result.put("nativeStatus", gatePassed ? "GATE_PASSED" : "GATE_FAILED");
        result.put("acceptancePassed", acceptancePassed);
        result.put("workspaceChanged", workspaceChanged);
        result.put("executionCalls", executionCalls);
        result.put("scratchProvisionedCount", scratchProvisioned);
        result.put("modelCalls", modelCalls);
        result.put("toolCalls", toolCalls);
        result.put("toolFailures", toolFailures);
        result.put("inputTokens", inputTokens);
        result.put("outputTokens", outputTokens);
        return result;
    }
}

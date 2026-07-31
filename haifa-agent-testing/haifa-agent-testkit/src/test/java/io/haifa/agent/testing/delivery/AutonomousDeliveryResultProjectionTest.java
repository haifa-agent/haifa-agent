package io.haifa.agent.testing.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.haifa.agent.testing.repository.RepositoryRevision;
import io.haifa.agent.testing.result.TestResultProjection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AutonomousDeliveryResultProjectionTest {
    private static final RepositoryRevision PRODUCT =
            new RepositoryRevision("1111111111111111111111111111111111111111", false);
    private static final RepositoryRevision CONFIG =
            new RepositoryRevision("2222222222222222222222222222222222222222", false);

    @Test
    void preservesNativeGateStatusAndProjectsUsageAndFailureCategory() {
        TestResultProjection.Batch batch = AutonomousDeliveryResultProjection.batch(
                suite(),
                combination(),
                PRODUCT,
                CONFIG,
                Instant.parse("2026-07-31T08:00:00Z"),
                List.of(result(true, true, true, true, true), result(false, false, true, true, true)));

        assertEquals("AUTONOMOUS_DELIVERY", batch.suiteSystem());
        assertEquals(TestResultProjection.Status.PASS, batch.results().get(0).status());
        assertEquals("GATE_PASSED", batch.results().get(0).nativeStatus());
        assertEquals("NONE", batch.results().get(0).failureCategory());
        assertEquals(TestResultProjection.Status.FAIL, batch.results().get(1).status());
        assertEquals("GATE_FAILED", batch.results().get(1).nativeStatus());
        assertEquals("DRIVER_CONTRACT", batch.results().get(1).failureCategory());
        assertEquals("2.4.0", batch.results().get(1).caseVersion());
        assertEquals("case-01/repeat-02", batch.results().get(1).evidenceRef());
        assertEquals(3L, batch.results().get(1).providerUsage().get("modelCalls"));
        assertEquals(false, batch.results().get(1).providerUsage().get("costKnown"));
    }

    @Test
    void projectionRejectsAbsoluteUriAndTraversalEvidenceReferences() {
        TestResultProjection projection = AutonomousDeliveryResultProjection.batch(
                        suite(),
                        combination(),
                        PRODUCT,
                        CONFIG,
                        Instant.parse("2026-07-31T08:00:00Z"),
                        List.of(result(true, true, true, true, true)))
                .results()
                .getFirst();

        for (String unsafe : List.of(
                "D:/host/evidence",
                "/var/evidence",
                "\\\\server\\share\\evidence",
                "file:///var/evidence",
                "case-01/..")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new TestResultProjection(
                            projection.schemaVersion(),
                            projection.suiteSystem(),
                            projection.suiteId(),
                            projection.caseId(),
                            projection.caseVersion(),
                            projection.repetition(),
                            projection.scope(),
                            projection.dependencyMode(),
                            projection.productEntry(),
                            projection.platform(),
                            projection.matrixCombination(),
                            projection.productCommit(),
                            projection.testConfigCommit(),
                            projection.status(),
                            projection.nativeStatus(),
                            projection.startedAt(),
                            projection.durationMillis(),
                            projection.providerUsage(),
                            unsafe,
                            projection.failureCategory()));
        }
    }

    @Test
    void projectionRequiresTheNativeGateStatusInsteadOfSynthesizingIt() {
        Map<String, Object> missingNativeStatus = result(true, true, true, true, true);
        missingNativeStatus.remove("nativeStatus");

        assertThrows(
                NullPointerException.class,
                () -> AutonomousDeliveryResultProjection.batch(
                        suite(),
                        combination(),
                        PRODUCT,
                        CONFIG,
                        Instant.parse("2026-07-31T08:00:00Z"),
                        List.of(missingNativeStatus)));
    }

    private static AutonomousDeliverySuiteManifest suite() {
        return new AutonomousDeliverySuiteManifest(
                1,
                "phase-3-v1",
                AutonomousDeliveryCaseCatalog.EXPECTED_CATALOG_ID,
                "PHASE_3",
                "autonomous-delivery-v1",
                "hidden-v1",
                null,
                new AutonomousDeliverySuiteManifest.Budget(60_000, 10, 20, 5, 1),
                List.of(new AutonomousDeliverySuiteManifest.CaseSelection("01", 2, true)));
    }

    private static AutonomousDeliveryMatrixManifest.Combination combination() {
        return new AutonomousDeliveryMatrixManifest.Combination(
                "windows-primary",
                "windows",
                "deepseek",
                "deepseek-v4-pro",
                "conpty",
                "host-guarded",
                "allow",
                "powershell",
                "TRUSTED_HOST_ONLY",
                "windows-host-trusted-v1",
                1);
    }

    private static Map<String, Object> result(
            boolean gatePassed,
            boolean driverPassed,
            boolean bounded,
            boolean acceptancePassed,
            boolean verificationPassed) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", "01");
        result.put("caseVersion", "2.4.0");
        result.put("repetition", gatePassed ? 1 : 2);
        result.put("gatePassed", gatePassed);
        result.put("nativeStatus", gatePassed ? "GATE_PASSED" : "GATE_FAILED");
        result.put("driverContractPassed", driverPassed);
        result.put("boundedConvergence", bounded);
        result.put("acceptancePassed", acceptancePassed);
        result.put("verificationPassed", verificationPassed);
        result.put("wallTimeSeconds", 1.25);
        result.put("modelCalls", 3L);
        result.put("toolCalls", 4L);
        result.put("toolFailures", 1L);
        result.put("inputTokens", 100L);
        result.put("outputTokens", 20L);
        return result;
    }
}

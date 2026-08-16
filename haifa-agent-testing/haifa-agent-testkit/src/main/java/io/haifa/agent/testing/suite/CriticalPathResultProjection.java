package io.haifa.agent.testing.suite;

import io.haifa.agent.testing.repository.RepositoryRevision;
import io.haifa.agent.testing.result.TestResultProjection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Maps native Critical Path Maven evidence into the versioned cross-suite projection. */
final class CriticalPathResultProjection {
    private static final String SUITE_SYSTEM = "CRITICAL_PATH";
    private static final String CASE_VERSION = "critical-path-v1";

    private CriticalPathResultProjection() {}

    static TestResultProjection.Batch batch(
            SuiteManifest suite,
            MatrixManifest.Combination combination,
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
            MatrixManifest.Combination combination,
            RepositoryRevision productRevision,
            RepositoryRevision testConfigRevision,
            Map<String, Object> result) {
        String caseId = String.valueOf(result.get("caseId"));
        CriticalPathCase testCase = CriticalPathCatalog.require(caseId);
        MavenTestEvidence.Status nativeStatus =
                (MavenTestEvidence.Status) Objects.requireNonNull(result.get("status"), "native status is required");
        TestResultProjection.Status status =
                switch (nativeStatus) {
                    case PASSED -> TestResultProjection.Status.PASS;
                    case NOT_RUN -> TestResultProjection.Status.NOT_RUN;
                    case SKIPPED -> TestResultProjection.Status.SKIPPED;
                    case FAILED, ERROR, TIMEOUT -> TestResultProjection.Status.FAIL;
                };
        String runId = String.valueOf(result.get("runId"));
        String evidenceRef =
                result.containsKey("evidenceRef") ? String.valueOf(result.get("evidenceRef")) : "runs/" + runId;
        return new TestResultProjection(
                1,
                SUITE_SYSTEM,
                suiteId,
                caseId,
                CASE_VERSION,
                ((Number) result.get("repetition")).intValue(),
                testCase.scope().name(),
                testCase.live() ? "LIVE" : "CONTROLLED",
                testCase.module(),
                combination.platform(),
                combination.id(),
                productRevision.commit(),
                testConfigRevision.commit(),
                status,
                nativeStatus.name(),
                null,
                ((Number) result.get("durationMillis")).longValue(),
                Map.of("costKnown", false),
                evidenceRef,
                nativeStatus == MavenTestEvidence.Status.PASSED ? "NONE" : nativeStatus.name());
    }
}

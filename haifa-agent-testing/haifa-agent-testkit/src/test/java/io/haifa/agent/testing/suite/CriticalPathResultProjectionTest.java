package io.haifa.agent.testing.suite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.haifa.agent.testing.repository.RepositoryRevision;
import io.haifa.agent.testing.result.TestResultProjection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CriticalPathResultProjectionTest {
    private static final RepositoryRevision PRODUCT =
            new RepositoryRevision("1111111111111111111111111111111111111111", false);
    private static final RepositoryRevision CONFIG =
            new RepositoryRevision("2222222222222222222222222222222222222222", false);

    @Test
    void preservesNativeMavenStatusWhileMappingCommonStatus() {
        TestResultProjection.Batch batch = CriticalPathResultProjection.batch(
                suite(),
                combination(),
                PRODUCT,
                CONFIG,
                Instant.parse("2026-07-31T08:00:00Z"),
                List.of(
                        result("CP-01", MavenTestEvidence.Status.PASSED),
                        result("CP-09", MavenTestEvidence.Status.TIMEOUT)));

        assertEquals(1, batch.schemaVersion());
        assertEquals("CRITICAL_PATH", batch.suiteSystem());
        assertEquals(TestResultProjection.Status.PASS, batch.results().get(0).status());
        assertEquals("PASSED", batch.results().get(0).nativeStatus());
        assertEquals("NONE", batch.results().get(0).failureCategory());
        assertEquals(TestResultProjection.Status.FAIL, batch.results().get(1).status());
        assertEquals("TIMEOUT", batch.results().get(1).nativeStatus());
        assertEquals("TIMEOUT", batch.results().get(1).failureCategory());
        assertEquals("runs/cp-09-r1", batch.results().get(1).evidenceRef());
        assertNull(batch.results().get(1).startedAt());
    }

    @Test
    void preservesExplicitNotRunStatusAndItsBatchEvidenceReference() {
        Map<String, Object> notRun = result("CP-09", MavenTestEvidence.Status.NOT_RUN);
        notRun.put("evidenceRef", "reports/projection-v1-result.json");

        TestResultProjection projection = CriticalPathResultProjection.batch(
                        suite(), combination(), PRODUCT, CONFIG, Instant.parse("2026-07-31T08:00:00Z"), List.of(notRun))
                .results()
                .getFirst();

        assertEquals(TestResultProjection.Status.NOT_RUN, projection.status());
        assertEquals("NOT_RUN", projection.nativeStatus());
        assertEquals("reports/projection-v1-result.json", projection.evidenceRef());
    }

    private static SuiteManifest suite() {
        return new SuiteManifest(
                1,
                "projection-v1",
                "primary-v1",
                new SuiteManifest.Budget(30, 3, 1),
                List.of(
                        new SuiteManifest.CaseSelection("CP-01", 1, true),
                        new SuiteManifest.CaseSelection("CP-09", 1, true)));
    }

    private static MatrixManifest.Combination combination() {
        return new MatrixManifest.Combination("windows-primary", "windows");
    }

    private static Map<String, Object> result(String caseId, MavenTestEvidence.Status status) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", caseId);
        result.put("repetition", 1);
        result.put("runId", caseId.toLowerCase() + "-r1");
        result.put("status", status);
        result.put("durationMillis", 123L);
        return result;
    }
}

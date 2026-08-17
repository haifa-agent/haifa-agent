package io.haifa.agent.testing.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.common.io.SecureFilePermissions;
import io.haifa.agent.testing.repository.RepositoryRevision;
import io.haifa.agent.testing.suite.AgentProfileManifest;
import io.haifa.agent.testing.suite.ResolvedAgentProfile;
import io.haifa.agent.testing.suite.SuiteManifest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunEvidenceWriterTest {
    @TempDir
    Path temporary;

    @Test
    void writesOneCommonEnvelopeAndLetsEvidenceFailureOverrideNativePass() throws Exception {
        Path product = Files.createDirectory(temporary.resolve("product"));
        Path configuration = Files.createDirectory(temporary.resolve("configuration"));
        Path evidence = Files.createDirectory(temporary.resolve("evidence"));
        Files.writeString(evidence.resolve("driver.log"), "local-test-secret");
        ResolvedRunContext.CriticalPath context = context(product, configuration, evidence.getParent());
        RunEvidenceWriter.NativeResult nativeResult = new RunEvidenceWriter.NativeResult(
                evidence,
                Instant.parse("2026-08-17T00:00:00Z"),
                Instant.parse("2026-08-17T00:00:01Z"),
                "SUITE_PASSED",
                "NONE",
                true,
                Map.of("tests", 1),
                List.of(),
                Map.of("schemaVersion", 2, "results", List.of()),
                List.of("local-test-secret"));
        try {
            RunEvidenceWriter.PublishedRun published = new RunEvidenceWriter()
                    .write(
                            context,
                            nativeResult,
                            java.math.BigDecimal.ONE,
                            context.productRevision(),
                            context.testConfigRevision());
            JsonNode result = new ObjectMapper()
                    .readTree(evidence.resolve("run-result.json").toFile());

            assertFalse(published.successful());
            assertEquals(2, result.path("schemaVersion").asInt());
            assertEquals("critical-path", result.path("suiteType").asText());
            assertEquals("SUITE_PASSED", result.path("nativeStatus").asText());
            assertEquals("EVIDENCE_FAILED", result.path("failureClassification").asText());
            assertEquals(
                    context.approvedDocument().plan().sha256(),
                    result.path("planSha256").asText());
            assertEquals(1, result.path("usageSummary").path("tests").asInt());
            assertTrue(result.path("nativeResult").has("results"));
            assertFalse(result.has("results"));
            assertTrue(Files.isRegularFile(evidence.resolve("secret-scan.json")));
            assertTrue(Files.isRegularFile(evidence.resolve("manifest.sha256")));
        } finally {
            makeWritable(evidence);
        }
    }

    private ResolvedRunContext.CriticalPath context(Path product, Path configuration, Path runRoot) throws Exception {
        RepositoryRevision productRevision = new RepositoryRevision("1".repeat(40), false);
        RepositoryRevision configurationRevision = new RepositoryRevision("2".repeat(40), false);
        String platformId = PlatformManifest.currentPlatform(System.getProperty("os.name")) + "-primary";
        PlatformManifest.PlatformProfile platform =
                new PlatformManifest.PlatformProfile(platformId, platformId.substring(0, platformId.indexOf('-')));
        PlatformManifest matrix = new PlatformManifest(2, "primary-v1", "explicit", List.of(platform));
        SuiteManifest suite = new SuiteManifest(
                1,
                "suite-v1",
                matrix.matrixId(),
                new SuiteManifest.Budget(30, 1, 1),
                List.of(new SuiteManifest.CaseSelection("CP-01", 1, true)));
        ResolvedAgentProfile profile = new ResolvedAgentProfile(
                new AgentProfileManifest(1, "profile-v1", productRevision.commit(), "profile.yaml", "b".repeat(64)),
                configuration.resolve("profile.yaml"),
                "c".repeat(64),
                List.of(),
                List.of());
        TestRunRequest request = new TestRunRequest(
                product, configuration, runRoot, suite.suiteId(), profile.profileId(), platform.id(), RunMode.LIVE);
        ResolvedTestPlan nativePlan = ResolvedTestPlan.freeze(Map.of("suiteType", "critical-path"));
        ExecutionPlanDocument document = ExecutionPlanDocument.freeze(
                request, nativePlan, new RunnerArtifact(1, "runner.jar", "d".repeat(64), RunnerArtifact.MAIN_CLASS));
        return new ResolvedRunContext.CriticalPath(
                document, suite, profile, platform, productRevision, configurationRevision);
    }

    private static void makeWritable(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (Files.isDirectory(path)) SecureFilePermissions.secureDirectory(path);
                else if (!Files.isSymbolicLink(path)) SecureFilePermissions.secureFile(path);
            }
        }
    }
}

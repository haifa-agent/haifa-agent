package io.haifa.agent.testing.suite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.haifa.agent.testing.harness.ExecutionPlanDocument;
import io.haifa.agent.testing.harness.HarnessPlanService;
import io.haifa.agent.testing.harness.ResolvedRunContext;
import io.haifa.agent.testing.harness.RunMode;
import io.haifa.agent.testing.harness.RunnerArtifact;
import io.haifa.agent.testing.harness.TestRunRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("slow")
class CriticalPathSuiteApplicationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void recordsEveryRemainingRepetitionAsNotRunAfterABlockingFailure() {
        SuiteManifest suite = new SuiteManifest(
                1,
                "blocking-v1",
                "primary-v1",
                new SuiteManifest.Budget(30, 3, 1),
                java.util.List.of(
                        new SuiteManifest.CaseSelection("CP-01", 2, true),
                        new SuiteManifest.CaseSelection("CP-02", 1, true),
                        new SuiteManifest.CaseSelection("CP-03", 1, true)));

        var results = CriticalPathSuiteApplication.notRunResultsAfter(suite, "CP-01", 1);

        assertEquals(3, results.size());
        assertEquals("CP-01", results.get(0).get("caseId"));
        assertEquals(2, results.get(0).get("repetition"));
        assertEquals(MavenTestEvidence.Status.NOT_RUN, results.get(0).get("status"));
        assertEquals("CP-03", results.get(2).get("caseId"));
        assertEquals("BLOCKING_CASE_FAILED", results.get(2).get("notRunReason"));
        assertEquals("CP-01", results.get(2).get("blockedByCaseId"));
        assertEquals("run-result.json", results.get(2).get("evidenceRef"));
    }

    @Test
    void routesFailsafeReportsIntoTheCaseEvidenceDirectory() throws Exception {
        Path projectRoot = Files.createDirectory(temporaryDirectory.resolve("reports-project"));
        Files.writeString(projectRoot.resolve(currentPlatform().equals("windows") ? "mvnw.cmd" : "mvnw"), "");
        Path reportsRoot = temporaryDirectory.resolve("case-evidence/failsafe-reports");

        var command = new CriticalPathSuiteApplication()
                .mavenCommand(projectRoot, CriticalPathCatalog.require("CP-01"), reportsRoot);

        assertTrue(command.contains("-Dhaifa.failsafe.reportsDirectory=" + reportsRoot));
        assertTrue(command.stream().noneMatch(value -> value.startsWith("-Dfailsafe.reportsDirectory=")));
        assertTrue(command.contains("-DskipUnitTests=true"));
        assertTrue(command.contains("-Denforcer.skip=true"));
        assertTrue(command.contains("-Djacoco.skip=true"));
        assertTrue(command.contains("-Dhaifa.cli.shade.skip=true"));
        assertTrue(command.stream().noneMatch("-Dshade.skip=true"::equals));
    }

    @Test
    void configuresDurablePersistenceForPersistenceCases() throws Exception {
        Path caseRoot = Files.createDirectory(temporaryDirectory.resolve("cp-10"));
        Map<String, String> environment = new HashMap<>();

        CriticalPathSuiteApplication.configureCaseEnvironment(
                environment, CriticalPathCatalog.require("CP-10"), caseRoot);

        assertEquals("SQLITE_WITH_JSONL", environment.get("HAIFA_PERSISTENCE_MODE"));
        assertEquals(
                caseRoot.resolve("persistence/runtime.db").toString(), environment.get("HAIFA_SQLITE_DATABASE_PATH"));
        assertEquals(caseRoot.resolve("persistence/transcripts").toString(), environment.get("HAIFA_TRANSCRIPT_ROOT"));
        assertTrue(Files.isDirectory(caseRoot.resolve("persistence/transcripts")));
    }

    @Test
    void rejectsIncompatibleEnvironmentDuringPlanOnlyPreflight() throws Exception {
        Path projectRoot = Files.createDirectory(temporaryDirectory.resolve("project"));
        Files.createDirectory(projectRoot.resolve("docs"));
        Path configRoot = Files.createDirectory(temporaryDirectory.resolve("test-config"));
        Path runRoot = temporaryDirectory.resolve("runs");
        writeRequiredAssetInventories(projectRoot, configRoot);
        Files.createDirectories(configRoot.resolve("suites"));
        Files.createDirectories(configRoot.resolve("matrices"));
        Files.createDirectories(configRoot.resolve("environments/cli"));
        Files.writeString(configRoot.resolve("matrices/primary-v1.yaml"), matrix());
        Files.writeString(
                configRoot.resolve("suites/critical-path-smoke-v1.yaml"),
                """
                schemaVersion: 1
                suiteId: critical-path-smoke-v1
                matrixRef: primary-v1
                budget:
                  maxWallTimeMinutes: 30
                  maxEstimatedCostUsd: 3
                  maxParallelExternalCalls: 1
                cases:
                  - caseId: CP-01
                    repetitions: 1
                    blocking: true
                """);
        Files.writeString(
                configRoot.resolve("environments/cli/interaction-live.yaml"),
                """
                persistence:
                  maximumPayloadBytes: 4194304
                """);
        commitRepository(projectRoot);
        commitRepository(configRoot);

        assertThrows(IllegalArgumentException.class, () -> new HarnessPlanService()
                .resolve(request(projectRoot, configRoot, runRoot), runner()));
    }

    @Test
    void rejectsDirtyTestConfigBeforeExecute() throws Exception {
        Path projectRoot = Files.createDirectory(temporaryDirectory.resolve("execute-project"));
        Files.createDirectory(projectRoot.resolve("docs"));
        Path configRoot = Files.createDirectory(temporaryDirectory.resolve("execute-test-config"));
        Path runRoot = temporaryDirectory.resolve("execute-runs");
        writeRequiredAssetInventories(projectRoot, configRoot);
        Files.createDirectories(configRoot.resolve("suites"));
        Files.createDirectories(configRoot.resolve("matrices"));
        Files.writeString(configRoot.resolve("matrices/primary-v1.yaml"), matrix());
        Files.writeString(
                configRoot.resolve("suites/critical-path-smoke-v1.yaml"),
                """
                schemaVersion: 1
                suiteId: critical-path-smoke-v1
                matrixRef: primary-v1
                budget:
                  maxWallTimeMinutes: 30
                  maxEstimatedCostUsd: 3
                  maxParallelExternalCalls: 1
                cases:
                  - caseId: CP-01
                    repetitions: 1
                    blocking: true
                """);
        commitRepository(projectRoot);
        writeAgentProfile(configRoot, projectRoot);
        commitRepository(configRoot);
        Files.writeString(configRoot.resolve("untracked-override.yaml"), "unsafe: true");

        ResolvedRunContext.CriticalPath context = context(projectRoot, configRoot, runRoot);
        assertThrows(IllegalArgumentException.class, () -> new CriticalPathSuiteApplication()
                .run(context, new BigDecimal("3.0"), Map.of()));
        assertEquals(false, Files.exists(runRoot));
    }

    @Test
    void rejectsExecuteWhenSuiteEstimatedCostExceedsIndependentApproval() throws Exception {
        Path projectRoot = Files.createDirectory(temporaryDirectory.resolve("cost-project"));
        Files.createDirectory(projectRoot.resolve("docs"));
        Path configRoot = Files.createDirectory(temporaryDirectory.resolve("cost-test-config"));
        Path runRoot = temporaryDirectory.resolve("cost-runs");
        writeRequiredAssetInventories(projectRoot, configRoot);
        Files.createDirectories(configRoot.resolve("suites"));
        Files.createDirectories(configRoot.resolve("matrices"));
        Files.writeString(configRoot.resolve("matrices/primary-v1.yaml"), matrix());
        Files.writeString(
                configRoot.resolve("suites/critical-path-smoke-v1.yaml"),
                """
                schemaVersion: 1
                suiteId: critical-path-smoke-v1
                matrixRef: primary-v1
                budget:
                  maxWallTimeMinutes: 30
                  maxEstimatedCostUsd: 3
                  maxParallelExternalCalls: 1
                cases:
                  - caseId: CP-01
                    repetitions: 1
                    blocking: true
                """);
        commitRepository(projectRoot);
        writeAgentProfile(configRoot, projectRoot);
        commitRepository(configRoot);

        ResolvedRunContext.CriticalPath context = context(projectRoot, configRoot, runRoot);
        assertThrows(IllegalArgumentException.class, () -> new CriticalPathSuiteApplication()
                .run(context, new BigDecimal("2.99"), Map.of("MODEL_API_KEY", "test-only-placeholder")));
        assertEquals(false, Files.exists(runRoot));
    }

    @Test
    void rejectsExecuteWhenResolvedPlanDoesNotMatchIndependentApproval() throws Exception {
        Path projectRoot = Files.createDirectory(temporaryDirectory.resolve("plan-project"));
        Files.createDirectory(projectRoot.resolve("docs"));
        Path configRoot = Files.createDirectory(temporaryDirectory.resolve("plan-test-config"));
        Path runRoot = temporaryDirectory.resolve("plan-runs");
        writeRequiredAssetInventories(projectRoot, configRoot);
        Files.createDirectories(configRoot.resolve("suites"));
        Files.createDirectories(configRoot.resolve("matrices"));
        Files.writeString(configRoot.resolve("matrices/primary-v1.yaml"), matrix());
        Files.writeString(
                configRoot.resolve("suites/critical-path-smoke-v1.yaml"),
                """
                schemaVersion: 1
                suiteId: critical-path-smoke-v1
                matrixRef: primary-v1
                budget:
                  maxWallTimeMinutes: 30
                  maxEstimatedCostUsd: 3
                  maxParallelExternalCalls: 1
                cases:
                  - caseId: CP-01
                    repetitions: 1
                    blocking: true
                """);
        commitRepository(projectRoot);
        writeAgentProfile(configRoot, projectRoot);
        commitRepository(configRoot);

        HarnessPlanService plans = new HarnessPlanService();
        ExecutionPlanDocument approved = plans.resolve(request(projectRoot, configRoot, runRoot), runner());
        Files.writeString(configRoot.resolve("changed-after-plan.yaml"), "changed: true");
        commitRepository(configRoot);

        assertThrows(IllegalArgumentException.class, () -> plans.resolveAndVerify(approved, runner()));
        assertEquals(false, Files.exists(runRoot));
    }

    private static ResolvedRunContext.CriticalPath context(Path projectRoot, Path configRoot, Path runRoot)
            throws Exception {
        HarnessPlanService plans = new HarnessPlanService();
        ExecutionPlanDocument approved = plans.resolve(request(projectRoot, configRoot, runRoot), runner());
        return (ResolvedRunContext.CriticalPath) plans.resolveAndVerify(approved, runner());
    }

    private static TestRunRequest request(Path projectRoot, Path configRoot, Path runRoot) {
        return new TestRunRequest(
                projectRoot,
                configRoot,
                runRoot,
                "critical-path-smoke-v1",
                "coding-primary",
                currentPlatform() + "-primary",
                RunMode.LIVE);
    }

    private static RunnerArtifact runner() {
        return new RunnerArtifact(1, "runner.jar", "a".repeat(64), "io.haifa.agent.testing.harness.TestHarnessMain");
    }

    private static void writeRequiredAssetInventories(Path projectRoot, Path configRoot) throws Exception {
        writeInventory(
                projectRoot.resolve("haifa-agent-testing/testing-assets-v2.json"),
                "haifa-agent",
                "haifa-agent-testing/testing-assets-v2.json");
        writeInventory(
                configRoot.resolve("assets/testing-assets-v2.json"),
                "haifa-agent-test-config",
                "assets/testing-assets-v2.json");
    }

    private static void writeAgentProfile(Path configRoot, Path projectRoot) throws Exception {
        Path configuration = configRoot.resolve("environments/coding-primary.yaml");
        Files.createDirectories(configuration.getParent());
        Files.writeString(
                configuration,
                """
                provider:
                  credentialRef: env://MODEL_API_KEY
                """);
        String configurationSha256 = io.haifa.agent.testing.evidence.Sha256Digests.file(configuration);
        Path profile = configRoot.resolve("agent-profiles/coding-primary.yaml");
        Files.createDirectories(profile.getParent());
        Files.writeString(
                profile,
                """
                schemaVersion: 1
                profileId: coding-primary
                compatibleAgentBaselineCommit: %s
                configurationRef: environments/coding-primary.yaml
                configurationSha256: %s
                """
                        .formatted(gitOutput(projectRoot, "rev-parse", "HEAD"), configurationSha256));
    }

    private static void writeInventory(Path inventory, String repositoryId, String inventoryPath) throws Exception {
        Files.createDirectories(inventory.getParent());
        Files.writeString(
                inventory,
                """
                {
                  "schemaVersion": 2,
                  "repositoryId": "%s",
                  "coverageRoots": [],
                  "assets": [
                    {
                      "assetId": "inventory",
                      "path": "%s",
                      "kind": "MANIFEST",
                      "lifecycle": "ACTIVE",
                      "disposition": "KEEP",
                      "owner": "testing-platform",
                      "referencedBy": [],
                      "replacement": "",
                      "rationale": "required entry-point inventory"
                    }
                  ]
                }
                """
                        .formatted(repositoryId, inventoryPath));
    }

    private static String matrix() {
        return """
                schemaVersion: 2
                matrixId: primary-v1
                strategy: explicit
                combinations:
                  - id: linux-primary
                    platform: linux
                  - id: macos-primary
                    platform: macos
                  - id: windows-primary
                    platform: windows
                """;
    }

    private static String currentPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) return "windows";
        if (os.contains("mac") || os.contains("darwin")) return "macos";
        return "linux";
    }

    private static void commitRepository(Path repository) throws Exception {
        Files.writeString(repository.resolve(".repository-fixture"), "test repository");
        runGit(repository, "init", "--quiet");
        runGit(repository, "config", "user.email", "tests@haifa.invalid");
        runGit(repository, "config", "user.name", "Haifa Tests");
        runGit(repository, "add", ".");
        runGit(repository, "commit", "--quiet", "-m", "fixture");
    }

    private static void runGit(Path repository, String... arguments) throws Exception {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repository.toString());
        command.addAll(java.util.List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        if (process.waitFor() != 0) {
            throw new IOException(
                    "git test setup failed: " + new String(output, java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static String gitOutput(Path repository, String... arguments) throws Exception {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repository.toString());
        command.addAll(java.util.List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        if (process.waitFor() != 0) {
            throw new IOException(
                    "git test setup failed: " + new String(output, java.nio.charset.StandardCharsets.UTF_8));
        }
        return new String(output, java.nio.charset.StandardCharsets.UTF_8).trim();
    }
}

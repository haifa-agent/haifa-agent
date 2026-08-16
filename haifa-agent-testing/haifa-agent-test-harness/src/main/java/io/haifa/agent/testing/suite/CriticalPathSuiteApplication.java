package io.haifa.agent.testing.suite;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.common.io.SecureFilePermissions;
import io.haifa.agent.testing.authorization.SecretPreflight;
import io.haifa.agent.testing.evidence.EvidenceFinalizer;
import io.haifa.agent.testing.evidence.EvidenceSecretScanner;
import io.haifa.agent.testing.harness.PlatformManifest;
import io.haifa.agent.testing.harness.PlatformManifestLoader;
import io.haifa.agent.testing.harness.ResolvedTestPlan;
import io.haifa.agent.testing.process.ProcessTreeCleanup;
import io.haifa.agent.testing.repository.RepositoryRevision;
import io.haifa.agent.testing.run.SafeRunRoot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Critical Path application service used by the shared harness entry point. */
public final class CriticalPathSuiteApplication {
    private final ObjectMapper json = new ObjectMapper();

    public int run(Options options, Map<String, String> environment) throws Exception {
        Path projectRoot = requireDirectory(options.projectRoot(), "project root");
        Path configRoot = requireDirectory(options.configRoot(), "test config root");
        Path runRoot = SafeRunRoot.requireExternalLocation(
                options.runRoot(), List.of(projectRoot, configRoot), "test run root");
        RepositoryRevision productRevision = RepositoryRevision.inspect(projectRoot);
        RepositoryRevision testConfigRevision = RepositoryRevision.inspect(configRoot);
        new EnvironmentConfigurationPreflight().validate(configRoot);
        SuiteManifest manifest = new SuiteManifestLoader().load(configRoot, options.suiteId());
        PlatformManifest matrix = new PlatformManifestLoader().load(configRoot, manifest.matrixRef());
        PlatformManifest.PlatformProfile combination = matrix.requireCombination(options.matrixCombination());
        ResolvedAgentProfile agentProfile = new AgentProfileManifestLoader().load(configRoot, options.agentProfile());
        productRevision.requireCompatibleBaseline(
                projectRoot, agentProfile.manifest().compatibleAgentBaselineCommit(), "Agent Profile");
        ResolvedTestPlan executionPlan = CriticalPathPlanResolver.resolve(
                manifest, combination, agentProfile, productRevision, testConfigRevision);
        String currentPlatform = currentPlatform();
        if (!combination.platform().equals(currentPlatform)) {
            throw new IllegalArgumentException("matrix combination "
                    + combination.id()
                    + " targets "
                    + combination.platform()
                    + " but current host is "
                    + currentPlatform);
        }

        if (manifest.budget().maxParallelExternalCalls() != 1) {
            throw new IllegalArgumentException(
                    "runner v1 executes external cases serially; maxParallelExternalCalls must be 1");
        }
        productRevision.requireClean("product repository");
        testConfigRevision.requireClean("test-config repository");
        double approvedMaxEstimatedCostUsd = requireApprovedMaxEstimatedCost(manifest, environment);
        String approvedExecutionPlanSha256 = requireApprovedExecutionPlan(executionPlan, environment);
        SecretPreflight.ResolvedSecrets requiredEnvironment =
                SecretPreflight.require(environment, agentProfile.requiredEnvironmentNames());
        List<String> selectedSecrets = agentProfile.credentialEnvironmentNames().stream()
                .map(requiredEnvironment::value)
                .toList();
        Instant startedAt = Instant.now();
        Path executionRoot = createExecutionRoot(runRoot, manifest.suiteId(), startedAt);
        System.out.println("Execution evidence id=" + executionRoot.getFileName());
        Instant deadline = startedAt.plus(Duration.ofMinutes(manifest.budget().maxWallTimeMinutes()));
        List<Map<String, Object>> results = new ArrayList<>();
        for (SuiteManifest.CaseSelection selection : manifest.cases()) {
            CriticalPathCase testCase = CriticalPathCatalog.require(selection.caseId());
            for (int repetition = 1; repetition <= selection.repetitions(); repetition++) {
                Map<String, Object> result = executeCase(
                        projectRoot,
                        configRoot,
                        executionRoot,
                        testCase,
                        repetition,
                        deadline,
                        environment,
                        combination,
                        agentProfile);
                results.add(result);
                if (!Boolean.TRUE.equals(result.get("successful")) && selection.blocking()) {
                    results.addAll(notRunResultsAfter(manifest, testCase.caseId(), repetition));
                    writeReport(
                            projectRoot,
                            configRoot,
                            executionRoot,
                            manifest,
                            combination,
                            agentProfile,
                            productRevision,
                            testConfigRevision,
                            approvedMaxEstimatedCostUsd,
                            approvedExecutionPlanSha256,
                            executionPlan,
                            startedAt,
                            results,
                            selectedSecrets);
                    return 1;
                }
            }
        }
        boolean evidenceSafe = writeReport(
                projectRoot,
                configRoot,
                executionRoot,
                manifest,
                combination,
                agentProfile,
                productRevision,
                testConfigRevision,
                approvedMaxEstimatedCostUsd,
                approvedExecutionPlanSha256,
                executionPlan,
                startedAt,
                results,
                selectedSecrets);
        return evidenceSafe && results.stream().allMatch(value -> Boolean.TRUE.equals(value.get("successful"))) ? 0 : 1;
    }

    static List<Map<String, Object>> notRunResultsAfter(
            SuiteManifest manifest, String blockingCaseId, int blockingRepetition) {
        List<Map<String, Object>> notRun = new ArrayList<>();
        boolean blockingExecutionFound = false;
        for (SuiteManifest.CaseSelection selection : manifest.cases()) {
            for (int repetition = 1; repetition <= selection.repetitions(); repetition++) {
                if (!blockingExecutionFound) {
                    blockingExecutionFound =
                            selection.caseId().equals(blockingCaseId) && repetition == blockingRepetition;
                    continue;
                }
                String runId = "not-run-" + selection.caseId().toLowerCase(Locale.ROOT) + "-r" + repetition;
                LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                result.put("caseId", selection.caseId());
                result.put("repetition", repetition);
                result.put("runId", runId);
                result.put("status", MavenTestEvidence.Status.NOT_RUN);
                result.put("successful", false);
                result.put("durationMillis", 0L);
                result.put("notRunReason", "BLOCKING_CASE_FAILED");
                result.put("blockedByCaseId", blockingCaseId);
                result.put("evidenceRef", "run-result.json");
                notRun.add(Map.copyOf(result));
            }
        }
        if (!blockingExecutionFound) {
            throw new IllegalArgumentException("blocking execution is not present in the suite");
        }
        return List.copyOf(notRun);
    }

    private Map<String, Object> executeCase(
            Path projectRoot,
            Path configRoot,
            Path executionRoot,
            CriticalPathCase testCase,
            int repetition,
            Instant deadline,
            Map<String, String> inheritedEnvironment,
            PlatformManifest.PlatformProfile combination,
            ResolvedAgentProfile agentProfile)
            throws Exception {
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isZero() || remaining.isNegative()) {
            throw new IllegalArgumentException("suite wall-time budget was exhausted before " + testCase.caseId());
        }
        String runId =
                testCase.caseId().toLowerCase(Locale.ROOT) + "-r" + repetition + "-" + java.util.UUID.randomUUID();
        Path caseRoot = Files.createDirectories(executionRoot.resolve("runs").resolve(runId));
        Path reportsRoot = caseRoot.resolve("failsafe-reports");
        List<String> command = mavenCommand(projectRoot, testCase, reportsRoot);
        ProcessBuilder builder =
                new ProcessBuilder(command).directory(projectRoot.toFile()).inheritIO();
        Map<String, String> childEnvironment = builder.environment();
        childEnvironment.putAll(inheritedEnvironment);
        childEnvironment.put("HAIFA_AGENT_ROOT", projectRoot.toString());
        childEnvironment.put("HAIFA_TEST_CONFIG_ROOT", configRoot.toString());
        childEnvironment.put("HAIFA_TEST_RUN_ROOT", executionRoot.toString());
        childEnvironment.put("HAIFA_TEST_MATRIX_COMBINATION", combination.id());
        childEnvironment.put("HAIFA_TEST_PLATFORM", combination.platform());
        childEnvironment.put("HAIFA_TEST_AGENT_PROFILE", agentProfile.profileId());
        childEnvironment.put(
                "HAIFA_TEST_AGENT_CONFIG", agentProfile.configurationPath().toString());
        childEnvironment.put("HAIFA_TEST_AGENT_ASSEMBLY_DIGEST", agentProfile.agentAssemblyDigest());
        childEnvironment.put("HAIFA_SUITE_EXECUTION", "true");
        childEnvironment.put("HAIFA_CODING_CLIENT_LIVE_TEST", "true");
        if (isCodingCase(testCase.caseId())) {
            Path codingRoot = Files.createDirectory(caseRoot.resolve("coding"));
            Files.writeString(
                    codingRoot.resolve(".haifa-cli-live-e2e-root"),
                    runId,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
            childEnvironment.put("HAIFA_FT_ENABLED", "true");
            childEnvironment.put("HAIFA_FT_MODE", "LIVE");
            childEnvironment.put("HAIFA_FT_RUN_ID", runId);
            childEnvironment.put("HAIFA_FT_ROOT", codingRoot.toString());
        }
        configureCaseEnvironment(childEnvironment, testCase, caseRoot);

        Instant caseStartedAt = Instant.now();
        System.out.printf("Running %s repetition=%d runId=%s%n", testCase.caseId(), repetition, runId);
        Process process = builder.start();
        ProcessTreeCleanup.Tracker processTracker = ProcessTreeCleanup.track(process);
        boolean completed = process.waitFor(Math.max(1, remaining.toSeconds()), TimeUnit.SECONDS);
        ProcessTreeCleanup.Result processCleanup = processTracker.converge(completed, Duration.ofSeconds(5));
        int exitCode = completed && !process.isAlive() ? process.exitValue() : 124;
        MavenTestEvidence evidence;
        String evidenceError = null;
        try {
            evidence = MavenTestEvidence.inspect(reportsRoot);
        } catch (Exception exception) {
            evidence = new MavenTestEvidence(0, 0, 0, 0, 0, List.of());
            evidenceError = exception.getClass().getSimpleName();
        }
        try {
            MavenTestEvidence.deleteRawReports(reportsRoot);
        } catch (IOException exception) {
            evidenceError = exception.getClass().getSimpleName();
        }
        MavenTestEvidence.Status status = evidence.status(completed, exitCode, evidenceError == null);
        if (status == MavenTestEvidence.Status.PASSED && !processCleanup.naturalExit()) {
            status = MavenTestEvidence.Status.ERROR;
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", testCase.caseId());
        result.put("repetition", repetition);
        result.put("runId", runId);
        result.put("status", status);
        result.put("successful", status == MavenTestEvidence.Status.PASSED);
        result.put("exitCode", exitCode);
        result.put("processCleanup", processCleanup);
        result.put("processTreeNaturalExit", processCleanup.naturalExit());
        result.put("testEvidence", evidence);
        if (evidenceError != null) {
            result.put("evidenceError", evidenceError);
        }
        result.put(
                "durationMillis", Duration.between(caseStartedAt, Instant.now()).toMillis());
        return result;
    }

    List<String> mavenCommand(Path projectRoot, CriticalPathCase testCase, Path reportsRoot) {
        Path wrapper = projectRoot.resolve(isWindows() ? "mvnw.cmd" : "mvnw");
        if (!Files.isRegularFile(wrapper)) throw new IllegalArgumentException("Maven wrapper is unavailable");
        return List.of(
                wrapper.toString(),
                "--batch-mode",
                "--no-transfer-progress",
                "-pl",
                testCase.module(),
                "-am",
                "-Pci-integration-only",
                "-DskipUnitTests=true",
                "-DskipITs=false",
                "-Denforcer.skip=true",
                "-Djacoco.skip=true",
                "-Dfailsafe.failIfNoSpecifiedTests=false",
                "-Dhaifa.failsafe.reportsDirectory=" + reportsRoot,
                "-Dit.test=" + testCase.testSelector(),
                "verify");
    }

    static void configureCaseEnvironment(Map<String, String> childEnvironment, CriticalPathCase testCase, Path caseRoot)
            throws IOException {
        if (!testCase.caseId().equals("CP-10") && !testCase.caseId().equals("CP-11")) return;
        Path persistenceRoot = Files.createDirectory(caseRoot.resolve("persistence"));
        childEnvironment.put("HAIFA_PERSISTENCE_MODE", "SQLITE_WITH_JSONL");
        childEnvironment.put(
                "HAIFA_SQLITE_DATABASE_PATH",
                persistenceRoot.resolve("runtime.db").toString());
        childEnvironment.put(
                "HAIFA_TRANSCRIPT_ROOT",
                Files.createDirectory(persistenceRoot.resolve("transcripts")).toString());
    }

    private boolean writeReport(
            Path projectRoot,
            Path configRoot,
            Path executionRoot,
            SuiteManifest manifest,
            PlatformManifest.PlatformProfile combination,
            ResolvedAgentProfile agentProfile,
            RepositoryRevision productRevision,
            RepositoryRevision testConfigRevision,
            double approvedMaxEstimatedCostUsd,
            String approvedExecutionPlanSha256,
            ResolvedTestPlan executionPlan,
            Instant startedAt,
            List<Map<String, Object>> results,
            java.util.Collection<String> selectedSecrets)
            throws IOException, InterruptedException {
        RepositoryRevision productRevisionAfter = RepositoryRevision.inspect(projectRoot);
        RepositoryRevision testConfigRevisionAfter = RepositoryRevision.inspect(configRoot);
        boolean repositoryStateStable =
                productRevision.equals(productRevisionAfter) && testConfigRevision.equals(testConfigRevisionAfter);
        EvidenceSecretScanner.Result secretScan = EvidenceSecretScanner.scan(executionRoot, selectedSecrets);
        json.writerWithDefaultPrettyPrinter()
                .writeValue(executionRoot.resolve("secret-scan.json").toFile(), secretScan);
        boolean testResultsSuccessful =
                results.stream().allMatch(value -> Boolean.TRUE.equals(value.get("successful")));
        LinkedHashMap<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("executionId", executionRoot.getFileName().toString());
        report.put("evidenceLayoutVersion", 1);
        report.put("evidenceManifest", "manifest.sha256");
        report.put("immutableEvidenceRequired", true);
        report.put("suiteId", manifest.suiteId());
        report.put("matrixRef", manifest.matrixRef());
        report.put("matrixCombination", combination);
        report.put("agentProfile", agentProfile.manifest());
        report.put("agentAssemblyDigest", agentProfile.agentAssemblyDigest());
        report.put("suiteBudget", manifest.budget());
        report.put("approvedMaxEstimatedCostUsd", approvedMaxEstimatedCostUsd);
        report.put("executionPlan", executionPlan);
        report.put("approvedExecutionPlanSha256", approvedExecutionPlanSha256);
        report.put("productCommit", productRevision.commit());
        report.put("testConfigCommit", testConfigRevision.commit());
        report.put("productRevision", productRevision);
        report.put("testConfigRevision", testConfigRevision);
        report.put("productRevisionAfter", productRevisionAfter);
        report.put("testConfigRevisionAfter", testConfigRevisionAfter);
        report.put("repositoryStateStable", repositoryStateStable);
        report.put("secretScan", secretScan);
        boolean successful = testResultsSuccessful && secretScan.passed() && repositoryStateStable;
        report.put("nativeStatus", successful ? "SUITE_PASSED" : "SUITE_FAILED");
        report.put("status", successful ? "PASSED" : "FAILED");
        report.put(
                "failureClassification",
                successful ? "NONE" : (secretScan.passed() ? "EXECUTION_FAILED" : "EVIDENCE_FAILED"));
        report.put("successful", successful);
        report.put("startedAt", startedAt.toString());
        report.put(
                "finishedAt", Instant.ofEpochMilli(System.currentTimeMillis()).toString());
        report.put("results", results);
        report.put("attachments", List.of());
        json.writerWithDefaultPrettyPrinter()
                .writeValue(executionRoot.resolve("run-result.json").toFile(), report);
        EvidenceFinalizer.finalizeEvidence(executionRoot);
        productRevision.requireUnchanged(productRevisionAfter, "product repository");
        testConfigRevision.requireUnchanged(testConfigRevisionAfter, "test-config repository");
        return secretScan.passed() && repositoryStateStable;
    }

    private static Path createExecutionRoot(Path runRoot, String suiteId, Instant startedAt) throws IOException {
        Files.createDirectories(runRoot);
        SecureFilePermissions.secureDirectory(runRoot);
        String executionId = "suite-" + suiteId + "-" + startedAt.toEpochMilli() + "-" + java.util.UUID.randomUUID();
        Path executionRoot = Files.createDirectory(runRoot.resolve(executionId));
        SecureFilePermissions.secureDirectory(executionRoot);
        return executionRoot;
    }

    private static double requireApprovedMaxEstimatedCost(SuiteManifest manifest, Map<String, String> environment) {
        String name = "HAIFA_TEST_APPROVED_MAX_ESTIMATED_COST_USD";
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required for execute");
        }
        double approved;
        try {
            approved = Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a positive finite number");
        }
        if (!Double.isFinite(approved) || approved <= 0) {
            throw new IllegalArgumentException(name + " must be a positive finite number");
        }
        double requested = manifest.budget().maxEstimatedCostUsd();
        if (requested > approved) {
            throw new IllegalArgumentException(
                    "suite estimated cost limit " + requested + " exceeds approved limit " + approved);
        }
        return approved;
    }

    private static String requireApprovedExecutionPlan(
            ResolvedTestPlan executionPlan, Map<String, String> environment) {
        String name = "HAIFA_TEST_APPROVED_PLAN_SHA256";
        String approved = environment.get(name);
        if (approved == null || !approved.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 for execute");
        }
        executionPlan.requireApproved(approved);
        return approved;
    }

    private static Path requireDirectory(Path value, String field) {
        Path normalized = Objects.requireNonNull(value, field + " must not be null")
                .toAbsolutePath()
                .normalize();
        if (!Files.isDirectory(normalized))
            throw new IllegalArgumentException(field + " must be an existing directory");
        return normalized;
    }

    private static boolean isCodingCase(String caseId) {
        return switch (caseId) {
            case "CP-02", "CP-03", "CP-04", "CP-05", "CP-06" -> true;
            default -> false;
        };
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String currentPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows";
        if (os.contains("mac") || os.contains("darwin")) return "macos";
        if (os.contains("linux")) return "linux";
        throw new IllegalArgumentException("unsupported host platform: " + os);
    }

    public record Options(
            Path projectRoot,
            Path configRoot,
            Path runRoot,
            String suiteId,
            String matrixCombination,
            String agentProfile) {}
}

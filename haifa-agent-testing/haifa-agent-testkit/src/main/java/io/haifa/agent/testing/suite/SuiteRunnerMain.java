package io.haifa.agent.testing.suite;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.common.io.SecureFilePermissions;
import io.haifa.agent.testing.assets.TestingAssetPreflight;
import io.haifa.agent.testing.authorization.SecretPreflight;
import io.haifa.agent.testing.evidence.EvidenceFinalizer;
import io.haifa.agent.testing.evidence.EvidenceSecretScanner;
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

/** Cross-platform plan/execute entry point used by the private test-config wrapper scripts. */
public final class SuiteRunnerMain {
    private final ObjectMapper json = new ObjectMapper();

    SuiteRunnerMain() {}

    public static void main(String[] arguments) {
        try {
            int exitCode = new SuiteRunnerMain().run(Options.parse(arguments), System.getenv());
            if (exitCode != 0) System.exit(exitCode);
        } catch (IllegalArgumentException exception) {
            System.err.println("Invalid suite request: " + exception.getMessage());
            System.exit(2);
        } catch (Exception exception) {
            System.err.println("Suite runner failed: " + exception.getClass().getSimpleName());
            System.exit(1);
        }
    }

    int run(Options options, Map<String, String> environment) throws Exception {
        Path projectRoot = requireDirectory(options.projectRoot(), "project root");
        Path configRoot = requireDirectory(options.configRoot(), "test config root");
        Path runRoot = SafeRunRoot.requireExternalLocation(
                options.runRoot(), List.of(projectRoot, configRoot), "test run root");
        RepositoryRevision productRevision = RepositoryRevision.inspect(projectRoot);
        RepositoryRevision testConfigRevision = RepositoryRevision.inspect(configRoot);
        new TestingAssetPreflight().validate(projectRoot, configRoot);
        new EnvironmentConfigurationPreflight().validate(configRoot);
        SuiteManifest manifest = new SuiteManifestLoader().load(configRoot, options.suiteId());
        MatrixManifest matrix = new MatrixManifestLoader().load(configRoot, manifest.matrixRef());
        MatrixManifest.Combination combination = matrix.requireCombination(options.matrixCombination());
        SuiteExecutionPlanFingerprint executionPlan = SuiteExecutionPlanFingerprint.create(manifest, combination);
        String currentPlatform = currentPlatform();
        if (!combination.platform().equals(currentPlatform)) {
            throw new IllegalArgumentException("matrix combination "
                    + combination.id()
                    + " targets "
                    + combination.platform()
                    + " but current host is "
                    + currentPlatform);
        }

        System.out.printf(
                "Suite %s matrix=%s combination=%s platform=%s model=%s/%s cases=%d "
                        + "budget=%dmin cost<=%.2f execute=%s%n",
                manifest.suiteId(),
                manifest.matrixRef(),
                combination.id(),
                combination.platform(),
                combination.modelProvider(),
                combination.modelId(),
                manifest.cases().size(),
                manifest.budget().maxWallTimeMinutes(),
                manifest.budget().maxEstimatedCostUsd(),
                options.execute());
        System.out.printf(
                "Revisions product=%s dirty=%s testConfig=%s dirty=%s%n",
                productRevision.commit(),
                productRevision.dirty(),
                testConfigRevision.commit(),
                testConfigRevision.dirty());
        System.out.printf(
                "Execution plan schema=%d sha256=%s%n", executionPlan.schemaVersion(), executionPlan.sha256());
        for (SuiteManifest.CaseSelection selection : manifest.cases()) {
            CriticalPathCase testCase = CriticalPathCatalog.require(selection.caseId());
            System.out.printf(
                    "  %s [%s] repetitions=%d blocking=%s -> %s %s%n",
                    testCase.caseId(),
                    testCase.scope(),
                    selection.repetitions(),
                    selection.blocking(),
                    testCase.module(),
                    testCase.testSelector());
        }
        if (!options.execute()) {
            System.out.println("Plan only. Pass --execute to call external services and run the selected tests.");
            return 0;
        }
        if (manifest.budget().maxParallelExternalCalls() != 1) {
            throw new IllegalArgumentException(
                    "runner v1 executes external cases serially; maxParallelExternalCalls must be 1");
        }
        productRevision.requireClean("product repository");
        testConfigRevision.requireClean("test-config repository");
        double approvedMaxEstimatedCostUsd = requireApprovedMaxEstimatedCost(manifest, environment);
        String approvedExecutionPlanSha256 = requireApprovedExecutionPlan(executionPlan, environment);
        SecretPreflight.ResolvedSecrets selectedSecrets =
                SecretPreflight.require(environment, requiredSecretNames(manifest));
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
                        combination);
                results.add(result);
                if (!Boolean.TRUE.equals(result.get("successful")) && selection.blocking()) {
                    writeReport(
                            projectRoot,
                            configRoot,
                            executionRoot,
                            manifest,
                            combination,
                            productRevision,
                            testConfigRevision,
                            approvedMaxEstimatedCostUsd,
                            approvedExecutionPlanSha256,
                            executionPlan,
                            startedAt,
                            results,
                            selectedSecrets.values());
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
                productRevision,
                testConfigRevision,
                approvedMaxEstimatedCostUsd,
                approvedExecutionPlanSha256,
                executionPlan,
                startedAt,
                results,
                selectedSecrets.values());
        return evidenceSafe && results.stream().allMatch(value -> Boolean.TRUE.equals(value.get("successful"))) ? 0 : 1;
    }

    private Map<String, Object> executeCase(
            Path projectRoot,
            Path configRoot,
            Path executionRoot,
            CriticalPathCase testCase,
            int repetition,
            Instant deadline,
            Map<String, String> inheritedEnvironment,
            MatrixManifest.Combination combination)
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
        childEnvironment.put("HAIFA_TEST_MODEL_PROVIDER", combination.modelProvider());
        childEnvironment.put("HAIFA_TEST_MODEL_ID", combination.modelId());
        childEnvironment.put("HAIFA_TEST_WEB_PROVIDER", combination.webProvider());
        childEnvironment.put("HAIFA_TEST_MCP_TARGET", combination.mcpTarget());
        childEnvironment.put("HAIFA_SUITE_EXECUTION", "true");
        childEnvironment.put("HAIFA_DEEPSEEK_LIVE_TEST", "true");
        childEnvironment.put("HAIFA_UTILITY_MCP_TEST", "true");
        childEnvironment.put("HAIFA_CLI_LIVE_E2E_TEST", "true");
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

    private List<String> mavenCommand(Path projectRoot, CriticalPathCase testCase, Path reportsRoot) {
        Path wrapper = projectRoot.resolve(isWindows() ? "mvnw.cmd" : "mvnw");
        if (!Files.isRegularFile(wrapper)) throw new IllegalArgumentException("Maven wrapper is unavailable");
        return List.of(
                wrapper.toString(),
                "--batch-mode",
                "--no-transfer-progress",
                "-pl",
                testCase.module(),
                "-am",
                "-Pci-integration",
                "-DskipITs=false",
                "-Dfailsafe.failIfNoSpecifiedTests=false",
                "-Dfailsafe.reportsDirectory=" + reportsRoot,
                "-Dit.test=" + testCase.testSelector(),
                "verify");
    }

    private boolean writeReport(
            Path projectRoot,
            Path configRoot,
            Path executionRoot,
            SuiteManifest manifest,
            MatrixManifest.Combination combination,
            RepositoryRevision productRevision,
            RepositoryRevision testConfigRevision,
            double approvedMaxEstimatedCostUsd,
            String approvedExecutionPlanSha256,
            SuiteExecutionPlanFingerprint executionPlan,
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
        report.put("schemaVersion", 3);
        report.put("executionId", executionRoot.getFileName().toString());
        report.put("evidenceLayoutVersion", 1);
        report.put("evidenceManifest", "manifest.sha256");
        report.put("immutableEvidenceRequired", true);
        report.put("suiteId", manifest.suiteId());
        report.put("matrixRef", manifest.matrixRef());
        report.put("matrixCombination", combination);
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
        report.put("successful", testResultsSuccessful && secretScan.passed() && repositoryStateStable);
        report.put("startedAt", startedAt.toString());
        report.put(
                "finishedAt", Instant.ofEpochMilli(System.currentTimeMillis()).toString());
        report.put("results", results);
        Path reportRoot = Files.createDirectories(executionRoot.resolve("reports"));
        json.writerWithDefaultPrettyPrinter()
                .writeValue(
                        reportRoot.resolve("result-projection-v1.json").toFile(),
                        CriticalPathResultProjection.batch(
                                manifest, combination, productRevision, testConfigRevision, Instant.now(), results));
        json.writerWithDefaultPrettyPrinter()
                .writeValue(
                        reportRoot.resolve(manifest.suiteId() + "-result.json").toFile(), report);
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
            SuiteExecutionPlanFingerprint executionPlan, Map<String, String> environment) {
        String name = "HAIFA_TEST_APPROVED_PLAN_SHA256";
        String approved = environment.get(name);
        if (approved == null || !approved.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 for execute");
        }
        if (!approved.equals(executionPlan.sha256())) {
            throw new IllegalArgumentException("resolved execution plan does not match " + name);
        }
        return approved;
    }

    private static List<String> requiredSecretNames(SuiteManifest manifest) {
        return manifest.cases().stream()
                .flatMap(selection -> CriticalPathCatalog.require(selection.caseId()).requiredSecrets().stream())
                .distinct()
                .toList();
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

    record Options(
            Path projectRoot,
            Path configRoot,
            Path runRoot,
            String suiteId,
            String matrixCombination,
            boolean execute) {
        static Options parse(String[] arguments) {
            Map<String, String> environment = System.getenv();
            Path projectRoot = path(environment.get("HAIFA_AGENT_ROOT"), Path.of("."));
            Path configRoot = path(environment.get("HAIFA_TEST_CONFIG_ROOT"), projectRoot.resolve("test-config"));
            Path runRoot = path(environment.get("HAIFA_TEST_RUN_ROOT"), null);
            String suiteId = environment.getOrDefault("HAIFA_TEST_SUITE", "pr-real-v1");
            String matrixCombination = environment.get("HAIFA_TEST_MATRIX_COMBINATION");
            boolean execute = false;
            for (int index = 0; index < arguments.length; index++) {
                switch (arguments[index]) {
                    case "--project-root" -> projectRoot = Path.of(requireArgument(arguments, ++index));
                    case "--config-root" -> configRoot = Path.of(requireArgument(arguments, ++index));
                    case "--run-root" -> runRoot = Path.of(requireArgument(arguments, ++index));
                    case "--suite" -> suiteId = requireArgument(arguments, ++index);
                    case "--matrix-combination" -> matrixCombination = requireArgument(arguments, ++index);
                    case "--execute" -> execute = true;
                    default -> throw new IllegalArgumentException("unknown argument: " + arguments[index]);
                }
            }
            if (runRoot == null) throw new IllegalArgumentException("HAIFA_TEST_RUN_ROOT or --run-root is required");
            if (matrixCombination == null || matrixCombination.isBlank()) {
                throw new IllegalArgumentException("HAIFA_TEST_MATRIX_COMBINATION or --matrix-combination is required");
            }
            return new Options(projectRoot, configRoot, runRoot, suiteId, matrixCombination, execute);
        }

        private static Path path(String value, Path fallback) {
            return value == null || value.isBlank() ? fallback : Path.of(value);
        }

        private static String requireArgument(String[] arguments, int index) {
            if (index >= arguments.length || arguments[index].isBlank()) {
                throw new IllegalArgumentException("missing argument value");
            }
            return arguments[index];
        }
    }
}

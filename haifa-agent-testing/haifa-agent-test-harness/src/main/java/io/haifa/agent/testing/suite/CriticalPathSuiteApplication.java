package io.haifa.agent.testing.suite;

import io.haifa.agent.common.io.SecureFilePermissions;
import io.haifa.agent.testing.authorization.SecretPreflight;
import io.haifa.agent.testing.harness.PlatformManifest;
import io.haifa.agent.testing.harness.ResolvedRunContext;
import io.haifa.agent.testing.harness.RunEvidenceWriter;
import io.haifa.agent.testing.process.ProcessTreeCleanup;
import java.io.IOException;
import java.math.BigDecimal;
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
import java.util.concurrent.TimeUnit;

/** Critical Path application service used by the shared harness entry point. */
public final class CriticalPathSuiteApplication {
    public RunEvidenceWriter.NativeResult run(
            ResolvedRunContext.CriticalPath context, BigDecimal approvedBudget, Map<String, String> environment)
            throws Exception {
        Path projectRoot = context.request().projectRoot();
        Path configRoot = context.request().configRoot();
        Path runRoot = context.request().runRoot();
        SuiteManifest manifest = context.suite();
        PlatformManifest.PlatformProfile combination = context.platform();
        ResolvedAgentProfile agentProfile = context.agentProfile();
        if (manifest.budget().maxParallelExternalCalls() != 1) {
            throw new IllegalArgumentException(
                    "runner v1 executes external cases serially; maxParallelExternalCalls must be 1");
        }
        context.productRevision().requireClean("product repository");
        context.testConfigRevision().requireClean("test-config repository");
        requireApprovedMaxEstimatedCost(manifest, approvedBudget);
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
                    return nativeResult(
                            executionRoot, manifest, combination, agentProfile, startedAt, results, selectedSecrets);
                }
            }
        }
        return nativeResult(executionRoot, manifest, combination, agentProfile, startedAt, results, selectedSecrets);
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
                "-Dhaifa.cli.shade.skip=true",
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

    private RunEvidenceWriter.NativeResult nativeResult(
            Path executionRoot,
            SuiteManifest manifest,
            PlatformManifest.PlatformProfile combination,
            ResolvedAgentProfile agentProfile,
            Instant startedAt,
            List<Map<String, Object>> results,
            List<String> selectedSecrets) {
        boolean successful = results.stream().allMatch(value -> Boolean.TRUE.equals(value.get("successful")));
        LinkedHashMap<String, Object> nativeResult = new LinkedHashMap<>();
        nativeResult.put("schemaVersion", 2);
        nativeResult.put("executionId", executionRoot.getFileName().toString());
        nativeResult.put("suiteId", manifest.suiteId());
        nativeResult.put("matrixRef", manifest.matrixRef());
        nativeResult.put("matrixCombination", combination);
        nativeResult.put("agentProfile", agentProfile.manifest());
        nativeResult.put("agentAssemblyDigest", agentProfile.agentAssemblyDigest());
        nativeResult.put("suiteBudget", manifest.budget());
        nativeResult.put("results", results);
        List<MavenTestEvidence> testEvidence = results.stream()
                .map(value -> value.get("testEvidence"))
                .filter(MavenTestEvidence.class::isInstance)
                .map(MavenTestEvidence.class::cast)
                .toList();
        Map<String, Object> usageSummary = Map.of(
                "caseExecutions", results.size(),
                "tests",
                        testEvidence.stream().mapToInt(MavenTestEvidence::tests).sum(),
                "failures",
                        testEvidence.stream()
                                .mapToInt(MavenTestEvidence::failures)
                                .sum(),
                "errors",
                        testEvidence.stream()
                                .mapToInt(MavenTestEvidence::errors)
                                .sum(),
                "skipped",
                        testEvidence.stream()
                                .mapToInt(MavenTestEvidence::skipped)
                                .sum());
        return new RunEvidenceWriter.NativeResult(
                executionRoot,
                startedAt,
                Instant.ofEpochMilli(System.currentTimeMillis()),
                successful ? "SUITE_PASSED" : "SUITE_FAILED",
                successful ? "NONE" : "EXECUTION_FAILED",
                successful,
                usageSummary,
                List.of(),
                nativeResult,
                selectedSecrets);
    }

    private static Path createExecutionRoot(Path runRoot, String suiteId, Instant startedAt) throws IOException {
        Files.createDirectories(runRoot);
        SecureFilePermissions.secureDirectory(runRoot);
        String executionId = "suite-" + suiteId + "-" + startedAt.toEpochMilli() + "-" + java.util.UUID.randomUUID();
        Path executionRoot = Files.createDirectory(runRoot.resolve(executionId));
        SecureFilePermissions.secureDirectory(executionRoot);
        return executionRoot;
    }

    private static void requireApprovedMaxEstimatedCost(SuiteManifest manifest, BigDecimal approved) {
        BigDecimal requested = BigDecimal.valueOf(manifest.budget().maxEstimatedCostUsd());
        if (requested.compareTo(approved) > 0) {
            throw new IllegalArgumentException(
                    "suite estimated cost limit " + requested + " exceeds approved limit " + approved);
        }
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
}

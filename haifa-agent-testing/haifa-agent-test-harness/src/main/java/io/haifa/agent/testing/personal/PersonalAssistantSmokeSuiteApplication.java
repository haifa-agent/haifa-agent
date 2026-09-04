package io.haifa.agent.testing.personal;

import io.haifa.agent.common.io.SecureFilePermissions;
import io.haifa.agent.testing.authorization.SecretPreflight;
import io.haifa.agent.testing.harness.ResolvedRunContext;
import io.haifa.agent.testing.harness.RunEvidenceWriter;
import io.haifa.agent.testing.process.ProcessTreeCleanup;
import io.haifa.agent.testing.suite.MavenTestEvidence;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs deterministic PA product smoke cases through the common Harness evidence lifecycle. */
public final class PersonalAssistantSmokeSuiteApplication {
    public RunEvidenceWriter.NativeResult run(ResolvedRunContext.PersonalAssistantSmoke context) throws Exception {
        context.productRevision().requireClean("product repository");
        context.testConfigRevision().requireClean("test-config repository");
        PersonalAssistantSmokeSuiteManifest suite = context.suite();
        if (context.request().mode().atLeast(io.haifa.agent.testing.harness.RunMode.LIVE)) {
            SecretPreflight.require(
                    Map.copyOf(System.getenv()), context.agentProfile().requiredEnvironmentNames());
        }
        Instant startedAt = Instant.now();
        Path executionRoot = createExecutionRoot(context.request().runRoot(), suite.suiteId(), startedAt);
        Instant deadline = startedAt.plus(Duration.ofMinutes(suite.budget().maxWallTimeMinutes()));
        List<Map<String, Object>> results = new ArrayList<>();
        for (PersonalAssistantSmokeSuiteManifest.CaseSelection selection : suite.cases()) {
            PersonalAssistantSmokeCase testCase = PersonalAssistantSmokeCatalog.require(selection.caseId());
            for (int repetition = 1; repetition <= selection.repetitions(); repetition++) {
                Map<String, Object> result = executeCase(context, executionRoot, testCase, repetition, deadline);
                results.add(result);
                if (!Boolean.TRUE.equals(result.get("successful")) && selection.blocking()) {
                    results.addAll(notRunResultsAfter(suite, testCase.caseId(), repetition));
                    return nativeResult(context, executionRoot, startedAt, results);
                }
            }
        }
        return nativeResult(context, executionRoot, startedAt, results);
    }

    private Map<String, Object> executeCase(
            ResolvedRunContext.PersonalAssistantSmoke context,
            Path executionRoot,
            PersonalAssistantSmokeCase testCase,
            int repetition,
            Instant deadline)
            throws Exception {
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isZero() || remaining.isNegative()) {
            throw new IllegalArgumentException("suite wall-time budget was exhausted before " + testCase.caseId());
        }
        String runId =
                testCase.caseId().toLowerCase(Locale.ROOT) + "-r" + repetition + "-" + java.util.UUID.randomUUID();
        Path caseRoot = Files.createDirectories(executionRoot.resolve("runs").resolve(runId));
        Path reportsRoot = caseRoot.resolve("surefire-reports");
        ProcessBuilder builder = new ProcessBuilder(
                        mavenCommand(context.request().projectRoot(), testCase, reportsRoot))
                .directory(context.request().projectRoot().toFile())
                .inheritIO();
        Map<String, String> environment = builder.environment();
        environment.put("HAIFA_AGENT_ROOT", context.request().projectRoot().toString());
        environment.put("HAIFA_TEST_CONFIG_ROOT", context.request().configRoot().toString());
        environment.put("HAIFA_TEST_RUN_ROOT", executionRoot.toString());
        environment.put("HAIFA_TEST_MATRIX_COMBINATION", context.platform().id());
        environment.put("HAIFA_TEST_PLATFORM", context.platform().platform());
        environment.put("HAIFA_TEST_AGENT_PROFILE", context.agentProfile().profileId());
        environment.put(
                "HAIFA_TEST_AGENT_CONFIG",
                context.agentProfile().configurationPath().toString());
        environment.put(
                "HAIFA_TEST_AGENT_ASSEMBLY_DIGEST", context.agentProfile().agentAssemblyDigest());
        environment.put("HAIFA_SUITE_EXECUTION", "true");
        environment.put(
                "HAIFA_PERSONAL_LIVE_SMOKE",
                Boolean.toString(context.request().mode().atLeast(io.haifa.agent.testing.harness.RunMode.LIVE)));

        Instant caseStartedAt = Instant.now();
        System.out.printf("Running %s repetition=%d runId=%s%n", testCase.caseId(), repetition, runId);
        Process process = builder.start();
        ProcessTreeCleanup.Tracker tracker = ProcessTreeCleanup.track(process);
        boolean completed = process.waitFor(Math.max(1, remaining.toSeconds()), TimeUnit.SECONDS);
        ProcessTreeCleanup.Result cleanup = tracker.converge(completed, Duration.ofSeconds(5));
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
        if (status == MavenTestEvidence.Status.PASSED && !cleanup.naturalExit())
            status = MavenTestEvidence.Status.ERROR;
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", testCase.caseId());
        result.put("repetition", repetition);
        result.put("runId", runId);
        result.put("status", status);
        result.put("successful", status == MavenTestEvidence.Status.PASSED);
        result.put("exitCode", exitCode);
        result.put("processCleanup", cleanup);
        result.put("processTreeNaturalExit", cleanup.naturalExit());
        result.put("testEvidence", evidence);
        if (evidenceError != null) result.put("evidenceError", evidenceError);
        result.put(
                "durationMillis", Duration.between(caseStartedAt, Instant.now()).toMillis());
        return Map.copyOf(result);
    }

    static List<String> mavenCommand(Path projectRoot, PersonalAssistantSmokeCase testCase, Path reportsRoot) {
        Path wrapper = projectRoot.resolve(isWindows() ? "mvnw.cmd" : "mvnw");
        if (!Files.isRegularFile(wrapper)) throw new IllegalArgumentException("Maven wrapper is unavailable");
        return List.of(
                wrapper.toString(),
                "--batch-mode",
                "--no-transfer-progress",
                "-pl",
                testCase.module(),
                "-am",
                "-Pslow-tests",
                "-Dtest=" + testCase.testSelector(),
                "-Dsurefire.failIfNoSpecifiedTests=false",
                "-Dhaifa.surefire.reportsDirectory=" + reportsRoot,
                "test");
    }

    private static List<Map<String, Object>> notRunResultsAfter(
            PersonalAssistantSmokeSuiteManifest suite, String blockingCaseId, int blockingRepetition) {
        List<Map<String, Object>> notRun = new ArrayList<>();
        boolean found = false;
        for (PersonalAssistantSmokeSuiteManifest.CaseSelection selection : suite.cases()) {
            for (int repetition = 1; repetition <= selection.repetitions(); repetition++) {
                if (!found) {
                    found = selection.caseId().equals(blockingCaseId) && repetition == blockingRepetition;
                    continue;
                }
                notRun.add(Map.of(
                        "caseId",
                        selection.caseId(),
                        "repetition",
                        repetition,
                        "runId",
                        "not-run-" + selection.caseId().toLowerCase(Locale.ROOT) + "-r" + repetition,
                        "status",
                        MavenTestEvidence.Status.NOT_RUN,
                        "successful",
                        false,
                        "durationMillis",
                        0L,
                        "notRunReason",
                        "BLOCKING_CASE_FAILED",
                        "blockedByCaseId",
                        blockingCaseId,
                        "evidenceRef",
                        "run-result.json"));
            }
        }
        if (!found) throw new IllegalArgumentException("blocking execution is not present in the suite");
        return List.copyOf(notRun);
    }

    private static RunEvidenceWriter.NativeResult nativeResult(
            ResolvedRunContext.PersonalAssistantSmoke context,
            Path executionRoot,
            Instant startedAt,
            List<Map<String, Object>> results) {
        boolean successful = results.stream().allMatch(value -> Boolean.TRUE.equals(value.get("successful")));
        List<MavenTestEvidence> evidence = results.stream()
                .map(value -> value.get("testEvidence"))
                .filter(MavenTestEvidence.class::isInstance)
                .map(MavenTestEvidence.class::cast)
                .toList();
        Map<String, Object> usage = Map.of(
                "caseExecutions", results.size(),
                "tests", evidence.stream().mapToInt(MavenTestEvidence::tests).sum(),
                "failures",
                        evidence.stream().mapToInt(MavenTestEvidence::failures).sum(),
                "errors", evidence.stream().mapToInt(MavenTestEvidence::errors).sum(),
                "skipped",
                        evidence.stream().mapToInt(MavenTestEvidence::skipped).sum());
        LinkedHashMap<String, Object> nativeResult = new LinkedHashMap<>();
        nativeResult.put("schemaVersion", 1);
        nativeResult.put("suiteId", context.suite().suiteId());
        nativeResult.put("matrixCombination", context.platform());
        nativeResult.put("agentProfile", context.agentProfile().manifest());
        nativeResult.put("results", results);
        return new RunEvidenceWriter.NativeResult(
                executionRoot,
                startedAt,
                Instant.now(),
                successful ? "SUITE_PASSED" : "SUITE_FAILED",
                successful ? "NONE" : "EXECUTION_FAILED",
                successful,
                usage,
                List.of(),
                nativeResult,
                List.of());
    }

    private static Path createExecutionRoot(Path runRoot, String suiteId, Instant startedAt) throws IOException {
        Files.createDirectories(runRoot);
        SecureFilePermissions.secureDirectory(runRoot);
        Path executionRoot = Files.createDirectory(runRoot.resolve(
                "suite-" + suiteId + "-" + startedAt.toEpochMilli() + "-" + java.util.UUID.randomUUID()));
        SecureFilePermissions.secureDirectory(executionRoot);
        return executionRoot;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}

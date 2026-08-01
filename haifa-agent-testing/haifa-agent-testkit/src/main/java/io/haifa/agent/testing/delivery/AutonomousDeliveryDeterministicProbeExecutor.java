package io.haifa.agent.testing.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.testing.process.ProcessTreeCleanup;
import io.haifa.agent.testing.suite.MavenTestEvidence;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Executes the provider-free Maven probes required before an Autonomous Delivery phase. */
final class AutonomousDeliveryDeterministicProbeExecutor {
    private static final Duration TIMEOUT = Duration.ofMinutes(10);
    private static final Duration TERMINATION_GRACE = Duration.ofSeconds(10);
    private static final List<String> PROVIDER_ENVIRONMENT_NAMES = List.of(
            "DEEPSEEK_API_KEY",
            "ALIYUN_IQS_API_KEY",
            "HAIFA_CONTINUATION_KEY",
            "HAIFA_DEEPSEEK_LIVE_TEST",
            "HAIFA_CLI_LIVE_E2E_TEST",
            "HAIFA_CLI_LIVE_E2E_PROVIDER");

    private final ObjectMapper json = new ObjectMapper();

    Map<String, Object> execute(Path gate, Path projectRoot, DeliveryHostProfile hostProfile, ProbeDefinition probe)
            throws Exception {
        Path repository = projectRoot.toAbsolutePath().normalize().toRealPath();
        Path repeat = gate.resolve(probe.evidenceDirectory()).resolve("repeat-01");
        Files.createDirectories(repeat);
        Path log = repeat.resolve("maven-test.log");
        Path reports = repeat.resolve("surefire-reports");
        ProcessBuilder builder = new ProcessBuilder(
                        command(hostProfile.requireMavenWrapper(repository), probe, reports))
                .directory(repository.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        isolateFromProviderSecrets(builder.environment());

        long started = System.nanoTime();
        Process process = builder.start();
        ProcessTreeCleanup.Tracker tracker = ProcessTreeCleanup.track(process);
        boolean finished = process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        int exitStatus = finished ? process.exitValue() : 124;
        ProcessTreeCleanup.Result cleanup = tracker.converge(finished, TERMINATION_GRACE);

        sanitizeMavenLog(log, repository, gate);
        LinkedHashMap<String, Object> result = deterministicTestEvidence(reports, finished, exitStatus);
        if (!cleanup.passed()) {
            result.put("status", MavenTestEvidence.Status.ERROR);
            result.put("passed", false);
            result.put("evidenceError", "PROCESS_TREE_CLEANUP_FAILED");
        }
        result.put("schemaVersion", 2);
        result.put("required", true);
        result.put("providerAccess", false);
        result.put("test", probe.testSelector());
        if (probe.scenarioCount() != null) {
            result.put("scenarioCount", probe.scenarioCount());
        }
        result.put("exitStatus", exitStatus);
        result.put("wallTimeMillis", Math.round((System.nanoTime() - started) / 1_000_000.0));
        json.writerWithDefaultPrettyPrinter()
                .writeValue(repeat.resolve("result.json").toFile(), result);
        return Map.copyOf(result);
    }

    static List<String> command(Path mavenWrapper, ProbeDefinition probe, Path reports) {
        List<String> command = new ArrayList<>();
        command.add(mavenWrapper.toString());
        command.add("--batch-mode");
        command.add("--no-transfer-progress");
        command.add("-pl");
        command.add(probe.module());
        command.add("-am");
        command.add("-Dtest=" + probe.testSelector());
        command.add("-Dsurefire.failIfNoSpecifiedTests=false");
        command.add("-Dhaifa.surefire.reportsDirectory=" + reports);
        command.add("test");
        return List.copyOf(command);
    }

    static void isolateFromProviderSecrets(Map<String, String> environment) {
        PROVIDER_ENVIRONMENT_NAMES.forEach(environment::remove);
    }

    static LinkedHashMap<String, Object> deterministicTestEvidence(Path reports, boolean finished, int exitStatus) {
        MavenTestEvidence evidence;
        String evidenceError = null;
        try {
            evidence = MavenTestEvidence.inspect(reports);
        } catch (Exception exception) {
            evidence = new MavenTestEvidence(0, 0, 0, 0, 0, List.of());
            evidenceError = exception.getClass().getSimpleName();
        }
        try {
            MavenTestEvidence.deleteRawReports(reports);
        } catch (IOException exception) {
            evidenceError = exception.getClass().getSimpleName();
        }
        MavenTestEvidence.Status status = evidence.status(finished, exitStatus, evidenceError == null);
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("passed", status == MavenTestEvidence.Status.PASSED);
        result.put("testEvidence", evidence);
        if (evidenceError != null) {
            result.put("evidenceError", evidenceError);
        }
        return result;
    }

    private static void sanitizeMavenLog(Path log, Path repository, Path gate) throws IOException {
        String content = Files.readString(log, StandardCharsets.UTF_8);
        content = redactPath(content, gate, "<GATE_ROOT>");
        content = redactPath(content, repository, "<PROJECT_ROOT>");
        content = redactPath(
                content,
                Path.of(System.getProperty("user.home")).toAbsolutePath().normalize(),
                "<USER_HOME>");
        Files.writeString(
                log, content, StandardCharsets.UTF_8, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static String redactPath(String content, Path path, String token) {
        String nativePath = path.toString();
        String slashPath = nativePath.replace('\\', '/');
        return content.replace(nativePath, token).replace(slashPath, token);
    }

    enum ProbeDefinition {
        READ_ONLY_ANALYZE(
                "deterministic-read-only-analyze",
                ":haifa-agent-cli",
                "LocalCodingAgentTest#stubAnalyzeRunUsesReadOnlyEvidenceWithoutRequiringWorkspaceChange",
                null),
        TRACE_REPLAY("deterministic-trace-replay", ":haifa-agent-runtime-core", "RuntimeControlTraceReplayTest", 10);

        private final String evidenceDirectory;
        private final String module;
        private final String testSelector;
        private final Integer scenarioCount;

        ProbeDefinition(String evidenceDirectory, String module, String testSelector, Integer scenarioCount) {
            this.evidenceDirectory = evidenceDirectory;
            this.module = module;
            this.testSelector = testSelector;
            this.scenarioCount = scenarioCount;
        }

        String evidenceDirectory() {
            return evidenceDirectory;
        }

        String module() {
            return module;
        }

        String testSelector() {
            return testSelector;
        }

        Integer scenarioCount() {
            return scenarioCount;
        }
    }
}

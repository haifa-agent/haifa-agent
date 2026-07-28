package io.haifa.agent.testing.suite;

import com.fasterxml.jackson.databind.ObjectMapper;
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
        Path runRoot = options.runRoot().toAbsolutePath().normalize();
        validateRunRoot(projectRoot, configRoot, runRoot);
        new EnvironmentConfigurationPreflight().validate(configRoot);
        SuiteManifest manifest = new SuiteManifestLoader().load(configRoot, options.suiteId());

        System.out.printf(
                "Suite %s matrix=%s cases=%d budget=%dmin cost<=%.2f execute=%s%n",
                manifest.suiteId(),
                manifest.matrixRef(),
                manifest.cases().size(),
                manifest.budget().maxWallTimeMinutes(),
                manifest.budget().maxEstimatedCostUsd(),
                options.execute());
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
        Files.createDirectories(runRoot);
        Instant startedAt = Instant.now();
        Instant deadline = startedAt.plus(Duration.ofMinutes(manifest.budget().maxWallTimeMinutes()));
        List<Map<String, Object>> results = new ArrayList<>();
        for (SuiteManifest.CaseSelection selection : manifest.cases()) {
            CriticalPathCase testCase = CriticalPathCatalog.require(selection.caseId());
            requireSecrets(testCase, environment);
            for (int repetition = 1; repetition <= selection.repetitions(); repetition++) {
                Map<String, Object> result =
                        executeCase(projectRoot, configRoot, runRoot, testCase, repetition, deadline, environment);
                results.add(result);
                if (!Boolean.TRUE.equals(result.get("successful")) && selection.blocking()) {
                    writeReport(projectRoot, configRoot, runRoot, manifest, startedAt, results);
                    return 1;
                }
            }
        }
        writeReport(projectRoot, configRoot, runRoot, manifest, startedAt, results);
        return results.stream().allMatch(value -> Boolean.TRUE.equals(value.get("successful"))) ? 0 : 1;
    }

    private Map<String, Object> executeCase(
            Path projectRoot,
            Path configRoot,
            Path runRoot,
            CriticalPathCase testCase,
            int repetition,
            Instant deadline,
            Map<String, String> inheritedEnvironment)
            throws Exception {
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isZero() || remaining.isNegative()) {
            throw new IllegalArgumentException("suite wall-time budget was exhausted before " + testCase.caseId());
        }
        String runId =
                testCase.caseId().toLowerCase(Locale.ROOT) + "-r" + repetition + "-" + java.util.UUID.randomUUID();
        Path caseRoot = Files.createDirectories(runRoot.resolve("runs").resolve(runId));
        List<String> command = mavenCommand(projectRoot, testCase);
        ProcessBuilder builder =
                new ProcessBuilder(command).directory(projectRoot.toFile()).inheritIO();
        Map<String, String> childEnvironment = builder.environment();
        childEnvironment.putAll(inheritedEnvironment);
        childEnvironment.put("HAIFA_AGENT_ROOT", projectRoot.toString());
        childEnvironment.put("HAIFA_TEST_CONFIG_ROOT", configRoot.toString());
        childEnvironment.put("HAIFA_TEST_RUN_ROOT", runRoot.toString());
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
        boolean completed = process.waitFor(Math.max(1, remaining.toSeconds()), TimeUnit.SECONDS);
        if (!completed) {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
        }
        int exitCode = completed ? process.exitValue() : 124;
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", testCase.caseId());
        result.put("repetition", repetition);
        result.put("runId", runId);
        result.put("successful", exitCode == 0);
        result.put("exitCode", exitCode);
        result.put(
                "durationMillis", Duration.between(caseStartedAt, Instant.now()).toMillis());
        return result;
    }

    private List<String> mavenCommand(Path projectRoot, CriticalPathCase testCase) {
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
                "-Dit.test=" + testCase.testSelector(),
                "verify");
    }

    private void writeReport(
            Path projectRoot,
            Path configRoot,
            Path runRoot,
            SuiteManifest manifest,
            Instant startedAt,
            List<Map<String, Object>> results)
            throws IOException {
        LinkedHashMap<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("suiteId", manifest.suiteId());
        report.put("matrixRef", manifest.matrixRef());
        report.put("productCommit", gitCommit(projectRoot));
        report.put("testConfigCommit", gitCommit(configRoot));
        report.put("startedAt", startedAt.toString());
        report.put("finishedAt", Instant.now().toString());
        report.put("results", results);
        Path reportRoot = Files.createDirectories(runRoot.resolve("reports"));
        json.writerWithDefaultPrettyPrinter()
                .writeValue(
                        reportRoot.resolve(manifest.suiteId() + "-result.json").toFile(), report);
    }

    private static String gitCommit(Path repository) {
        try {
            Process process = new ProcessBuilder("git", "-C", repository.toString(), "rev-parse", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            String value = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.waitFor() == 0 ? value : "unavailable";
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException)
                Thread.currentThread().interrupt();
            return "unavailable";
        }
    }

    private static void requireSecrets(CriticalPathCase testCase, Map<String, String> environment) {
        List<String> missing = testCase.requiredSecrets().stream()
                .filter(name -> {
                    String value = environment.get(name);
                    return value == null || value.isBlank();
                })
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(testCase.caseId() + " requires environment variables " + missing);
        }
    }

    private static void validateRunRoot(Path projectRoot, Path configRoot, Path runRoot) {
        if (!runRoot.isAbsolute()) throw new IllegalArgumentException("test run root must be absolute");
        if (overlaps(runRoot, projectRoot) || overlaps(runRoot, configRoot)) {
            throw new IllegalArgumentException("test run root must be outside both Git repositories");
        }
    }

    private static boolean overlaps(Path left, Path right) {
        return left.startsWith(right) || right.startsWith(left);
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

    record Options(Path projectRoot, Path configRoot, Path runRoot, String suiteId, boolean execute) {
        static Options parse(String[] arguments) {
            Map<String, String> environment = System.getenv();
            Path projectRoot = path(environment.get("HAIFA_AGENT_ROOT"), Path.of("."));
            Path configRoot = path(environment.get("HAIFA_TEST_CONFIG_ROOT"), projectRoot.resolve("test-config"));
            Path runRoot = path(environment.get("HAIFA_TEST_RUN_ROOT"), null);
            String suiteId = environment.getOrDefault("HAIFA_TEST_SUITE", "pr-real-v1");
            boolean execute = false;
            for (int index = 0; index < arguments.length; index++) {
                switch (arguments[index]) {
                    case "--project-root" -> projectRoot = Path.of(requireArgument(arguments, ++index));
                    case "--config-root" -> configRoot = Path.of(requireArgument(arguments, ++index));
                    case "--run-root" -> runRoot = Path.of(requireArgument(arguments, ++index));
                    case "--suite" -> suiteId = requireArgument(arguments, ++index);
                    case "--execute" -> execute = true;
                    default -> throw new IllegalArgumentException("unknown argument: " + arguments[index]);
                }
            }
            if (runRoot == null) throw new IllegalArgumentException("HAIFA_TEST_RUN_ROOT or --run-root is required");
            return new Options(projectRoot, configRoot, runRoot, suiteId, execute);
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

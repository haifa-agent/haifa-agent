package io.haifa.agent.testing.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Serial, repository-external Phase 1 production Gate and evidence collector. */
final class AutonomousDeliveryPhaseOneGate {
    private static final DateTimeFormatter GATE_TIME =
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final Pattern ITERATION = Pattern.compile("(?:^| )iteration=([0-9]+)(?: |$)");
    private static final String DRIVER_RESOURCE = "autonomous-delivery/run_terminal.py";

    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock;

    AutonomousDeliveryPhaseOneGate(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    Path run(
            Path campaign,
            String buildCommit,
            AutonomousDeliverySuiteManifest suite,
            AutonomousDeliveryCaseCatalog catalog,
            Path cliJar,
            Map<String, Path> toolchainHomes)
            throws Exception {
        if (!suite.phase().equals("PHASE_1")) {
            throw new IllegalArgumentException("phase-1-gate requires a PHASE_1 suite");
        }
        requireSecret("DEEPSEEK_API_KEY");
        requireSecret("HAIFA_CONTINUATION_KEY");
        Path jar = requireFile(cliJar, "CLI JAR");
        Map<String, Path> homes = validateToolchains(toolchainHomes);
        Path gate = campaign.resolve("phase-1")
                .resolve("build-" + buildCommit)
                .resolve("gate-" + GATE_TIME.format(clock.instant()));
        Files.createDirectories(gate.getParent());
        Files.createDirectory(gate);
        Path driver = gate.resolve("terminal-driver.py");
        copyResource(DRIVER_RESOURCE, driver);

        List<Map<String, Object>> results = new ArrayList<>();
        boolean successful = true;
        for (AutonomousDeliverySuiteManifest.CaseSelection selection : suite.cases()) {
            AutonomousDeliveryCase testCase = catalog.require(selection.caseId());
            for (int repetition = 1; repetition <= selection.repetitions(); repetition++) {
                Path repeat = gate.resolve("case-" + testCase.caseId()).resolve("repeat-%02d".formatted(repetition));
                Map<String, Object> result =
                        runRepeat(repeat, buildCommit, suite, testCase, repetition, jar, homes, driver);
                results.add(result);
                if (selection.blocking() && !Boolean.TRUE.equals(result.get("gatePassed"))) {
                    successful = false;
                }
            }
        }
        int executionCalls = results.stream()
                .mapToInt(result -> ((Number) result.get("executionCalls")).intValue())
                .sum();
        int scratchProvisioned = results.stream()
                .mapToInt(result -> ((Number) result.get("scratchProvisionedCount")).intValue())
                .sum();
        boolean scratchExercised = executionCalls > 0 && executionCalls == scratchProvisioned;
        successful &= scratchExercised;
        Files.delete(driver);
        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("schemaVersion", 1);
        summary.put("phase", suite.phase());
        summary.put("suiteId", suite.suiteId());
        summary.put("buildCommit", buildCommit);
        summary.put("finishedAt", Instant.now(clock).toString());
        summary.put("successful", successful);
        summary.put("executionCalls", executionCalls);
        summary.put("scratchProvisionedCount", scratchProvisioned);
        summary.put("scratchExercised", scratchExercised);
        summary.put("results", results);
        writeJson(gate.resolve("phase-summary.json"), summary);
        writeBaselineComparison(campaign, gate, buildCommit, summary);
        AutonomousDeliveryHarnessMain.writeManifest(gate);
        AutonomousDeliveryHarnessMain.makeReadOnly(gate);
        if (!successful) {
            throw new IllegalStateException("Phase 1 gate failed; immutable evidence: " + gate);
        }
        return gate;
    }

    private void writeBaselineComparison(Path campaign, Path gate, String buildCommit, Map<String, Object> summary)
            throws IOException {
        Path baselineIndex = campaign.resolve("baseline").resolve("historical-evidence-index.json");
        JsonNode baselines = json.readTree(baselineIndex.toFile());
        LinkedHashMap<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("schemaVersion", 1);
        comparison.put("comparisonType", "PHASE_1_GATE_VS_READ_ONLY_HISTORICAL_EVIDENCE");
        comparison.put("buildCommit", buildCommit);
        comparison.put("gateEvidence", campaign.relativize(gate).toString().replace('\\', '/'));
        comparison.put("generatedAt", Instant.now(clock).toString());
        comparison.put("historicalEvidence", baselines);
        comparison.put(
                "interpretation",
                "Historical entries are integrity-pinned evidence references; Phase 1 outcomes are not "
                        + "treated as performance-equivalent unless case and harness versions match.");
        comparison.put("phaseOutcome", summary);
        Path output = campaign.resolve("comparison")
                .resolve("phase-1-build-" + buildCommit + "-" + gate.getFileName() + "-vs-baseline.json");
        if (Files.exists(output)) {
            throw new IOException("Phase 1 baseline comparison already exists");
        }
        writeJson(output, comparison);
    }

    private Map<String, Object> runRepeat(
            Path repeat,
            String buildCommit,
            AutonomousDeliverySuiteManifest suite,
            AutonomousDeliveryCase testCase,
            int repetition,
            Path cliJar,
            Map<String, Path> homes,
            Path driver)
            throws Exception {
        Files.createDirectories(repeat.getParent());
        Files.createDirectory(repeat);
        Path immutableCase = repeat.resolve("immutable-case");
        new AutonomousDeliveryFixtureStore().materializeCase(testCase, immutableCase);
        Path workspace = repeat.resolve("workspace");
        copyTree(immutableCase.resolve("base-workspace"), workspace);
        String before = workspaceDigest(workspace);
        initializeGit(workspace);
        Files.writeString(repeat.resolve("workspace-before.sha256"), before + "\n", StandardOpenOption.CREATE_NEW);
        Path transcripts = Files.createDirectory(repeat.resolve("transcripts"));
        Path configuration = repeat.resolve("terminal.yaml");
        Files.writeString(
                configuration, configuration(suite, homes), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Path driverResult = repeat.resolve("driver-result.json");
        writeRunManifest(repeat, buildCommit, suite, testCase, repetition, cliJar, homes);

        long startedNanos = System.nanoTime();
        ProcessBuilder builder = new ProcessBuilder(
                pythonExecutable(),
                driver.toString(),
                cliJar.toString(),
                workspace.toString(),
                configuration.toString(),
                repeat.resolve("trace-detail.jsonl").toString(),
                immutableCase.resolve("prompt.txt").toString(),
                immutableCase.resolve("acceptance.py").toString(),
                driverResult.toString(),
                Long.toString(TimeUnit.MILLISECONDS.toSeconds(suite.budget().maxWallTimeMillis())));
        builder.redirectErrorStream(true);
        builder.redirectOutput(repeat.resolve("session.cast").toFile());
        Map<String, String> environment = builder.environment();
        environment.put("JAVA_HOME", homes.get("java").toString());
        environment.put(
                "PATH",
                String.join(
                        ":",
                        homes.get("python").resolve("bin").toString(),
                        homes.get("java").resolve("bin").toString(),
                        homes.get("node").resolve("bin").toString(),
                        homes.get("go").resolve("bin").toString(),
                        "/usr/bin",
                        "/bin",
                        "/usr/sbin",
                        "/sbin"));
        environment.put("HAIFA_PERSISTENCE_MODE", "SQLITE_WITH_JSONL");
        environment.put(
                "HAIFA_SQLITE_DATABASE_PATH", repeat.resolve("runtime.db").toString());
        environment.put("HAIFA_TRANSCRIPT_ROOT", transcripts.toString());
        environment.put("TERM", "xterm-256color");
        Process process = builder.start();
        boolean finished = process.waitFor(suite.budget().maxWallTimeMillis() + 120_000, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly();
        }
        int driverExit = finished ? process.exitValue() : 124;
        double wallSeconds = (System.nanoTime() - startedNanos) / 1_000_000_000.0;
        JsonNode driverEvidence = Files.isRegularFile(driverResult) ? json.readTree(driverResult.toFile()) : null;

        String after = workspaceDigest(workspace);
        Files.writeString(repeat.resolve("workspace-after.sha256"), after + "\n", StandardOpenOption.CREATE_NEW);
        runCommand(workspace, repeat.resolve("workspace.diff"), "git", "diff", "--binary", "--no-ext-diff");
        AutonomousDeliveryRuntimeEvidenceReader.Evidence authoritative =
                new AutonomousDeliveryRuntimeEvidenceReader(json).read(repeat.resolve("runtime.db"));
        long wallTimeMillis = Math.round(wallSeconds * 1000.0);
        int iterations = maximumIteration(repeat.resolve("trace-detail.jsonl"));
        boolean withinBudget = authoritative.modelCalls() <= suite.budget().maxModelCalls()
                && authoritative.toolCalls() <= suite.budget().maxToolCalls()
                && iterations <= suite.budget().maxIterations()
                && wallTimeMillis <= suite.budget().maxWallTimeMillis();
        Acceptance acceptance = acceptance(driverEvidence, testCase);
        boolean acceptancePassed = acceptance.passed();
        boolean bounded = finished
                && wallSeconds < TimeUnit.MILLISECONDS.toSeconds(suite.budget().maxWallTimeMillis())
                && withinBudget
                && authoritative.maximumClusterAttempts() <= 4;
        boolean caseTenConverged = testCase.caseId().equals("10") && bounded && wallSeconds < 900;
        boolean gatePassed = (acceptancePassed || caseTenConverged)
                && authoritative.scratchSatisfied()
                && authoritative.terminalStateObserved()
                && bounded;

        writeJson(repeat.resolve("acceptance-result.json"), acceptance.artifact());
        Map<String, Object> resultUsage = resultUsage(authoritative, wallTimeMillis);
        LinkedHashMap<String, Object> usageArtifact = new LinkedHashMap<>(resultUsage);
        usageArtifact.put("schemaVersion", 1);
        usageArtifact.put("iterations", iterations);
        usageArtifact.put("withinBudget", withinBudget);
        writeJson(repeat.resolve("usage.json"), usageArtifact);
        writeJson(
                repeat.resolve("failure-clusters.json"),
                Map.of(
                        "schemaVersion",
                        1,
                        "clusters",
                        authoritative.failureClusters(),
                        "maximumAttempts",
                        authoritative.maximumClusterAttempts()));
        writeJson(
                repeat.resolve("progress-evidence.json"),
                Map.of("schemaVersion", 1, "meaningfulProgress", authoritative.progress()));
        writeJson(
                repeat.resolve("completion-evidence.json"),
                Map.of(
                        "schemaVersion",
                        1,
                        "acceptancePassed",
                        acceptancePassed,
                        "caseTenBoundedConvergence",
                        caseTenConverged,
                        "terminalStateObserved",
                        authoritative.terminalStateObserved()));
        long descendantsAlive =
                process.descendants().filter(ProcessHandle::isAlive).count();
        boolean cleanupPassed = descendantsAlive == 0;
        writeJson(
                repeat.resolve("process-cleanup.json"),
                Map.of(
                        "schemaVersion",
                        1,
                        "driverExitStatus",
                        driverExit,
                        "timedOut",
                        !finished,
                        "descendantsAlive",
                        descendantsAlive,
                        "passed",
                        cleanupPassed));
        gatePassed &= cleanupPassed;
        Map<String, Object> secretScan = secretScan(repeat);
        writeJson(repeat.resolve("secret-scan.json"), secretScan);
        gatePassed &= Boolean.TRUE.equals(secretScan.get("passed"));
        writeJson(
                repeat.resolve("result.json"),
                Map.of(
                        "schemaVersion",
                        1,
                        "caseId",
                        testCase.caseId(),
                        "caseVersion",
                        testCase.caseVersion(),
                        "repeat",
                        repetition,
                        "termination",
                        authoritative.termination(),
                        "successful",
                        gatePassed,
                        "hiddenAcceptance",
                        acceptancePassed ? "PASS" : "FAIL",
                        "usage",
                        resultUsage,
                        "evidence",
                        Map.of(
                                "workspaceChanged",
                                !before.equals(after),
                                "validationAttempted",
                                authoritative.validationAttempted(),
                                "diffInspected",
                                authoritative.diffInspected(),
                                "failureAtomicity",
                                "NOT_APPLICABLE")));
        LinkedHashMap<String, Object> summaryResult = new LinkedHashMap<>();
        summaryResult.put("caseId", testCase.caseId());
        summaryResult.put("caseVersion", testCase.caseVersion());
        summaryResult.put("repetition", repetition);
        summaryResult.put("driverExitStatus", driverExit);
        summaryResult.put("wallTimeSeconds", Math.round(wallSeconds * 1000.0) / 1000.0);
        summaryResult.put("acceptancePassed", acceptancePassed);
        summaryResult.put("boundedConvergence", bounded);
        summaryResult.put("executionCalls", authoritative.executionCalls());
        summaryResult.put("scratchProvisionedCount", authoritative.scratchProvisionedCount());
        summaryResult.put("scratchCleanupFailures", authoritative.scratchCleanupFailures());
        summaryResult.put("scratchSatisfied", authoritative.scratchSatisfied());
        summaryResult.put("maximumFailureClusterAttempts", authoritative.maximumClusterAttempts());
        summaryResult.put("gatePassed", gatePassed);
        Files.deleteIfExists(driverResult);
        AutonomousDeliveryHarnessMain.writeManifest(repeat);
        return Map.copyOf(summaryResult);
    }

    private int maximumIteration(Path trace) throws IOException {
        int iterations = 0;
        if (Files.isRegularFile(trace)) {
            for (String line : Files.readAllLines(trace, StandardCharsets.UTF_8)) {
                Matcher matcher = ITERATION.matcher(line);
                while (matcher.find()) iterations = Math.max(iterations, Integer.parseInt(matcher.group(1)));
            }
        }
        return iterations;
    }

    private Acceptance acceptance(JsonNode driverEvidence, AutonomousDeliveryCase testCase) {
        LinkedHashMap<String, Boolean> checks = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        boolean passed = false;
        if (driverEvidence != null) {
            try {
                JsonNode grader =
                        json.readTree(driverEvidence.path("acceptanceStdout").asText(""));
                JsonNode graderChecks = grader.path("checks");
                if (graderChecks.isObject()) {
                    graderChecks.fields().forEachRemaining(entry -> {
                        if (entry.getValue().isBoolean())
                            checks.put(entry.getKey(), entry.getValue().asBoolean());
                    });
                }
                JsonNode graderFailures = grader.path("failures");
                if (graderFailures.isArray()) {
                    graderFailures.forEach(value -> {
                        String text = value.asText("");
                        if (!text.isBlank() && text.length() <= 512 && failures.size() < 128) {
                            failures.add(text);
                        }
                    });
                }
                passed = driverEvidence.path("acceptanceExitStatus").asInt(-1) == 0
                        && grader.path("passed").asBoolean(false);
            } catch (IOException ignored) {
                // Converted into bounded, non-sensitive evidence below.
            }
        }
        if (checks.isEmpty()) {
            checks.put("graderOutputValid", false);
            failures.clear();
            failures.add("GRADER_OUTPUT_INVALID");
            passed = false;
        }
        LinkedHashMap<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("schemaVersion", 1);
        artifact.put("caseId", testCase.caseId());
        artifact.put("caseVersion", testCase.caseVersion());
        artifact.put("passed", passed);
        artifact.put("checks", Map.copyOf(checks));
        artifact.put("failures", List.copyOf(failures));
        return new Acceptance(passed, Map.copyOf(artifact));
    }

    private static Map<String, Object> resultUsage(
            AutonomousDeliveryRuntimeEvidenceReader.Evidence evidence, long wallTimeMillis) {
        return Map.of(
                "modelCalls",
                evidence.modelCalls(),
                "toolCalls",
                evidence.toolCalls(),
                "toolFailures",
                evidence.toolFailures(),
                "inputTokens",
                evidence.inputTokens(),
                "outputTokens",
                evidence.outputTokens(),
                "wallTimeMillis",
                wallTimeMillis,
                "costKnown",
                false);
    }

    private Map<String, Object> secretScan(Path repeat) throws IOException {
        byte[] modelSecret = System.getenv("DEEPSEEK_API_KEY").getBytes(StandardCharsets.UTF_8);
        byte[] continuationSecret = System.getenv("HAIFA_CONTINUATION_KEY").getBytes(StandardCharsets.UTF_8);
        List<String> findings = new ArrayList<>();
        try (var files = Files.walk(repeat)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().equals("secret-scan.json"))
                    .filter(path -> !path.getFileName().toString().equals("manifest.sha256"))
                    .toList()) {
                byte[] content = Files.readAllBytes(file);
                if (contains(content, modelSecret) || contains(content, continuationSecret)) {
                    findings.add(repeat.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
        return Map.of("schemaVersion", 1, "passed", findings.isEmpty(), "findingPaths", findings);
    }

    private void writeRunManifest(
            Path repeat,
            String buildCommit,
            AutonomousDeliverySuiteManifest suite,
            AutonomousDeliveryCase testCase,
            int repetition,
            Path cliJar,
            Map<String, Path> homes)
            throws IOException {
        LinkedHashMap<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        manifest.put("phase", suite.phase());
        manifest.put("suiteId", suite.suiteId());
        manifest.put("buildCommit", buildCommit);
        manifest.put("caseId", testCase.caseId());
        manifest.put("caseVersion", testCase.caseVersion());
        manifest.put("repetition", repetition);
        manifest.put("startedAt", Instant.now(clock).toString());
        manifest.put("modelProvider", "deepseek");
        manifest.put("modelId", "deepseek-chat");
        manifest.put("sandboxProfile", "local-native");
        manifest.put("cliJarSha256", Sha256Digests.file(cliJar));
        LinkedHashMap<String, String> toolchainDigests = new LinkedHashMap<>();
        homes.forEach((name, path) ->
                toolchainDigests.put(name, Sha256Digests.bytes(path.toString().getBytes(StandardCharsets.UTF_8))));
        manifest.put("toolchainPathDigests", toolchainDigests);
        manifest.put("budget", suite.budget());
        writeJson(repeat.resolve("run-manifest.json"), manifest);
    }

    private static String configuration(AutonomousDeliverySuiteManifest suite, Map<String, Path> homes) {
        return """
                model:
                  providerId: deepseek
                  modelId: deepseek-chat
                  endpoint: https://api.deepseek.com
                  credentialRef: env://DEEPSEEK_API_KEY
                tools:
                  enabled: [file.list, file.stat, file.read, file.search, file.create, file.write, file.delete, file.move, execution.run]
                skills:
                  allowed: [task-planning, result-verification]
                approval:
                  mode: auto
                execution:
                  provider: local-native
                  network: deny
                  shell: auto
                  defaultTimeoutMillis: 120000
                  maxTimeoutMillis: 600000
                  maxOutputLines: 2000
                  maxOutputBytes: 102400
                  maxProcesses: 8
                  inheritEnvironment: [PATH, HOME, JAVA_HOME]
                  extraPathPolicies:
                    - { id: java-home, path: %s, readOnly: true }
                    - { id: python-home, path: %s, readOnly: true }
                    - { id: node-home, path: %s, readOnly: true }
                    - { id: go-home, path: %s, readOnly: true }
                runtime:
                  maxIterations: %d
                  maxToolCalls: %d
                  maxWallTimeMillis: %d
                persistence:
                  mode: SQLITE_WITH_JSONL
                  protectorRef: env://HAIFA_CONTINUATION_KEY
                  busyTimeoutMillis: 5000
                  maximumPayloadBytes: 1048576
                """
                .formatted(
                        yamlPath(homes.get("java")),
                        yamlPath(homes.get("python")),
                        yamlPath(homes.get("node")),
                        yamlPath(homes.get("go")),
                        suite.budget().maxIterations(),
                        suite.budget().maxToolCalls(),
                        suite.budget().maxWallTimeMillis());
    }

    private static String yamlPath(Path path) {
        return "'" + path.toString().replace("'", "''") + "'";
    }

    private static Map<String, Path> validateToolchains(Map<String, Path> values) throws IOException {
        LinkedHashMap<String, Path> result = new LinkedHashMap<>();
        for (String name : List.of("java", "python", "node", "go")) {
            Path value = values.get(name);
            if (value == null) throw new IllegalArgumentException("--" + name + "-home is required");
            Path home = value.toAbsolutePath().normalize().toRealPath();
            if (!Files.isDirectory(home)) throw new IllegalArgumentException(name + " home must be a directory");
            result.put(name, home);
        }
        return Map.copyOf(result);
    }

    private static Path requireFile(Path value, String label) throws IOException {
        if (value == null) throw new IllegalArgumentException(label + " is required");
        Path file = value.toAbsolutePath().normalize().toRealPath();
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException(label + " must be a regular file");
        return file;
    }

    private static void requireSecret(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be injected in the process environment");
        }
    }

    private static String pythonExecutable() {
        return System.getenv().getOrDefault("PYTHON_EXECUTABLE", "python3");
    }

    private static void initializeGit(Path workspace) throws Exception {
        runCommand(workspace, null, "git", "init", "-q");
        runCommand(workspace, null, "git", "config", "user.name", "Haifa Gate Fixture");
        runCommand(workspace, null, "git", "config", "user.email", "fixture@invalid.local");
        runCommand(workspace, null, "git", "add", ".");
        runCommand(workspace, null, "git", "commit", "-q", "-m", "baseline fixture");
    }

    private static void runCommand(Path directory, Path output, String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        if (output != null) builder.redirectOutput(output.toFile());
        else builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            process.destroyForcibly();
            throw new IOException("local evidence command failed");
        }
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        Files.createDirectory(destination);
        try (var paths = Files.walk(source)) {
            for (Path path : paths.sorted().toList()) {
                if (path.equals(source)) continue;
                Path target =
                        destination.resolve(source.relativize(path).toString()).normalize();
                if (!target.startsWith(destination)) throw new IOException("workspace copy escaped destination");
                if (Files.isDirectory(path)) Files.createDirectory(target);
                else Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    private static String workspaceDigest(Path root) throws IOException {
        java.security.MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile)
                    .filter(path -> !root.relativize(path).startsWith(".git"))
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                    .toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                digest.update(relative.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Sha256Digests.file(file).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static void copyResource(String name, Path destination) throws IOException {
        try (InputStream input =
                AutonomousDeliveryPhaseOneGate.class.getClassLoader().getResourceAsStream(name)) {
            if (input == null) throw new IOException("Gate driver resource is unavailable");
            Files.copy(input, destination);
        }
    }

    private void writeJson(Path path, Object value) throws IOException {
        json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || haystack.length < needle.length) return false;
        outer:
        for (int index = 0; index <= haystack.length - needle.length; index++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (haystack[index + offset] != needle[offset]) continue outer;
            }
            return true;
        }
        return false;
    }

    private record Acceptance(boolean passed, Map<String, Object> artifact) {}
}

package io.haifa.agent.testing.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.testing.evidence.Sha256Digests;
import io.haifa.agent.testing.process.ProcessTreeCleanup;
import io.haifa.agent.testing.repository.RepositoryRevision;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Executes one isolated Autonomous Delivery case repetition and hands its facts to evidence collectors. */
final class AutonomousDeliveryRepeatExecutor {
    private static final Pattern ITERATION = Pattern.compile("(?:^| )iteration=([0-9]+)(?: |$)");

    private final ObjectMapper json;
    private final Clock clock;
    private final AutonomousDeliveryPhaseThreeVerificationCollector phaseThreeVerificationCollector;

    AutonomousDeliveryRepeatExecutor(ObjectMapper json, Clock clock) {
        this.json = Objects.requireNonNull(json, "json must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.phaseThreeVerificationCollector = new AutonomousDeliveryPhaseThreeVerificationCollector(json);
    }

    Map<String, Object> execute(
            Path repeat,
            String buildCommit,
            AutonomousDeliverySuiteManifest suite,
            AutonomousDeliveryCase testCase,
            int repetition,
            Path cliJar,
            DeliveryToolchainSet toolchains,
            DeliveryHostProfile hostProfile,
            AutonomousDeliveryMatrixManifest.Combination matrixCombination,
            RepositoryRevision productRevision,
            RepositoryRevision testConfigRevision,
            Path driver,
            boolean nodeDriver,
            AutonomousDeliveryPhasePolicy phasePolicy,
            Collection<String> selectedSecrets)
            throws Exception {
        Files.createDirectories(repeat.getParent());
        Files.createDirectory(repeat);
        Path immutableCase = repeat.resolve("immutable-case");
        new AutonomousDeliveryFixtureStore().materializeCase(testCase, immutableCase);
        Path workspace = repeat.resolve("workspace");
        copyTree(immutableCase.resolve("base-workspace"), workspace);
        String before = workspaceDigest(workspace);
        initializeGit(workspace, toolchains);
        Files.writeString(repeat.resolve("workspace-before.sha256"), before + "\n", StandardOpenOption.CREATE_NEW);
        Path transcripts = Files.createDirectory(repeat.resolve("transcripts"));
        Path configuration = repeat.resolve("terminal.yaml");
        Files.writeString(
                configuration,
                DeliveryCliConfigurationFactory.render(suite, toolchains, hostProfile),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
        Path driverResult = repeat.resolve("driver-result.json");
        Path recording = repeat.resolve("session.cast");
        writeRunManifest(
                repeat,
                buildCommit,
                suite,
                testCase,
                repetition,
                cliJar,
                toolchains,
                hostProfile,
                matrixCombination,
                productRevision,
                testConfigRevision);

        long startedNanos = System.nanoTime();
        List<String> driverCommand = new ArrayList<>();
        driverCommand.add((nodeDriver ? toolchains.nodeExecutable() : toolchains.pythonExecutable()).toString());
        driverCommand.add(driver.toString());
        if (nodeDriver) {
            driverCommand.add(toolchains.javaExecutable().toString());
            driverCommand.add(toolchains.pythonExecutable().toString());
        }
        driverCommand.addAll(List.of(
                cliJar.toString(),
                workspace.toString(),
                configuration.toString(),
                repeat.resolve("trace-detail.jsonl").toString(),
                immutableCase.resolve("prompt.txt").toString(),
                immutableCase.resolve("acceptance.py").toString(),
                recording.toString(),
                driverResult.toString(),
                Long.toString(TimeUnit.MILLISECONDS.toSeconds(suite.budget().maxWallTimeMillis()))));
        ProcessBuilder builder = new ProcessBuilder(driverCommand);
        builder.redirectErrorStream(true);
        builder.redirectOutput(repeat.resolve("driver.log").toFile());
        Map<String, String> environment = builder.environment();
        environment.put("JAVA_HOME", toolchains.javaHome().toString());
        environment.put("PATH", toolchains.minimalPath());
        environment.put("HAIFA_PERSISTENCE_MODE", "SQLITE_WITH_JSONL");
        environment.put(
                "HAIFA_SQLITE_DATABASE_PATH", repeat.resolve("runtime.db").toString());
        environment.put("HAIFA_TRANSCRIPT_ROOT", transcripts.toString());
        environment.put("TERM", "xterm-256color");
        Process process = builder.start();
        ProcessTreeCleanup.Tracker processTracker = ProcessTreeCleanup.track(process);
        boolean finished = process.waitFor(suite.budget().maxWallTimeMillis() + 120_000, TimeUnit.MILLISECONDS);
        ProcessTreeCleanup.Result processCleanup = processTracker.converge(finished, Duration.ofSeconds(10));
        int driverExit = finished && !process.isAlive() ? process.exitValue() : 124;
        double wallSeconds = (System.nanoTime() - startedNanos) / 1_000_000_000.0;
        JsonNode driverEvidence = Files.isRegularFile(driverResult) ? json.readTree(driverResult.toFile()) : null;
        TerminalDriverResultContract.Validation driverContract =
                TerminalDriverResultContract.validate(driverEvidence, hostProfile.terminalBackend(), recording);

        String after = workspaceDigest(workspace);
        Files.writeString(repeat.resolve("workspace-after.sha256"), after + "\n", StandardOpenOption.CREATE_NEW);
        runGit(toolchains, workspace, repeat.resolve("workspace.diff"), "diff", "--binary", "--no-ext-diff");
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
        boolean preliminaryGatePassed = (acceptancePassed || caseTenConverged)
                && driverContract.passed()
                && authoritative.scratchSatisfied()
                && authoritative.terminalStateObserved()
                && bounded;
        AutonomousDeliveryPhaseThreeVerificationCollector.Result phaseThree = phasePolicy.requiresExternalVerification()
                ? phaseThreeVerificationCollector.collect(
                        repeat,
                        new AutonomousDeliveryPhaseThreeVerificationCollector.Input(
                                AutonomousDeliveryRepeatEvidenceCollector.CaseMetadata.from(testCase),
                                acceptance.passed(),
                                acceptance.checks(),
                                acceptance.failures(),
                                before,
                                after,
                                authoritative.scratchCleanupFailures(),
                                finished))
                : AutonomousDeliveryPhaseThreeVerificationCollector.notRequired();
        preliminaryGatePassed &= phaseThree.passed();

        AutonomousDeliveryRepeatEvidenceCollector.Result collected = new AutonomousDeliveryRepeatEvidenceCollector(json)
                .collect(
                        repeat,
                        new AutonomousDeliveryRepeatEvidenceCollector.Input(
                                AutonomousDeliveryRepeatEvidenceCollector.CaseMetadata.from(testCase),
                                repetition,
                                driverExit,
                                driverContract,
                                wallSeconds,
                                wallTimeMillis,
                                acceptancePassed,
                                acceptance.artifact(),
                                bounded,
                                caseTenConverged,
                                preliminaryGatePassed,
                                authoritative,
                                processCleanup,
                                iterations,
                                withinBudget,
                                !before.equals(after),
                                phaseThree.passed(),
                                phaseThree.atomicity()),
                        selectedSecrets);
        return collected.summary();
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
                        if (entry.getValue().isBoolean()) {
                            checks.put(entry.getKey(), entry.getValue().asBoolean());
                        }
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
        return new Acceptance(passed, Map.copyOf(artifact), Map.copyOf(checks), List.copyOf(failures));
    }

    private void writeRunManifest(
            Path repeat,
            String buildCommit,
            AutonomousDeliverySuiteManifest suite,
            AutonomousDeliveryCase testCase,
            int repetition,
            Path cliJar,
            DeliveryToolchainSet toolchains,
            DeliveryHostProfile hostProfile,
            AutonomousDeliveryMatrixManifest.Combination matrixCombination,
            RepositoryRevision productRevision,
            RepositoryRevision testConfigRevision)
            throws IOException {
        LinkedHashMap<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 3);
        manifest.put("phase", suite.phase());
        manifest.put("suiteId", suite.suiteId());
        manifest.put("matrixRef", suite.matrixRef());
        manifest.put("matrixCombination", matrixCombination);
        manifest.put("buildCommit", buildCommit);
        manifest.put("productRevision", productRevision);
        manifest.put("testConfigRevision", testConfigRevision);
        manifest.put("caseId", testCase.caseId());
        manifest.put("caseVersion", testCase.caseVersion());
        manifest.put("repetition", repetition);
        manifest.put("startedAt", now().toString());
        manifest.put("modelProvider", matrixCombination.modelProvider());
        manifest.put("modelId", matrixCombination.modelId());
        manifest.put("platform", hostProfile.platform());
        manifest.put("terminalBackend", hostProfile.terminalBackend());
        manifest.put("sandboxProfile", hostProfile.executionProvider());
        manifest.put("networkPolicy", hostProfile.networkPolicy());
        manifest.put("shell", hostProfile.shell());
        manifest.put("isolationAssurance", hostProfile.isolationAssurance());
        manifest.put("cliJarSha256", Sha256Digests.file(cliJar));
        LinkedHashMap<String, String> toolchainDigests = new LinkedHashMap<>();
        toolchains
                .executablePaths()
                .forEach((name, path) -> toolchainDigests.put(
                        name, Sha256Digests.bytes(path.toString().getBytes(StandardCharsets.UTF_8))));
        manifest.put("toolchainPathDigests", toolchainDigests);
        manifest.put("budget", suite.budget());
        writeJson(repeat.resolve("run-manifest.json"), manifest);
    }

    private Instant now() {
        return Instant.ofEpochMilli(clock.millis());
    }

    private static void initializeGit(Path workspace, DeliveryToolchainSet toolchains) throws Exception {
        runGit(toolchains, workspace, null, "init", "-q");
        runGit(toolchains, workspace, null, "config", "user.name", "Haifa Gate Fixture");
        runGit(toolchains, workspace, null, "config", "user.email", "fixture@invalid.local");
        runGit(toolchains, workspace, null, "config", "core.autocrlf", "false");
        runGit(toolchains, workspace, null, "config", "core.filemode", "false");
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows")) {
            runGit(toolchains, workspace, null, "config", "core.longpaths", "true");
        }
        runGit(toolchains, workspace, null, "add", ".");
        runGit(toolchains, workspace, null, "commit", "-q", "-m", "baseline fixture");
    }

    private static void runGit(DeliveryToolchainSet toolchains, Path directory, Path output, String... arguments)
            throws Exception {
        List<String> command = new ArrayList<>();
        command.add(toolchains.gitExecutable().toString());
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        if (output != null) {
            builder.redirectOutput(output.toFile());
        } else {
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        }
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
                if (path.equals(source)) {
                    continue;
                }
                Path target =
                        destination.resolve(source.relativize(path).toString()).normalize();
                if (!target.startsWith(destination)) {
                    throw new IOException("workspace copy escaped destination");
                }
                if (Files.isDirectory(path)) {
                    Files.createDirectory(target);
                } else {
                    Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
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

    private void writeJson(Path path, Object value) throws IOException {
        json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }

    private record Acceptance(
            boolean passed, Map<String, Object> artifact, Map<String, Boolean> checks, List<String> failures) {}
}

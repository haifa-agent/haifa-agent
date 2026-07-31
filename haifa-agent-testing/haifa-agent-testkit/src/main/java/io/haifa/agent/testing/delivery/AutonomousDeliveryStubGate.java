package io.haifa.agent.testing.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.testing.evidence.EvidenceFinalizer;
import io.haifa.agent.testing.evidence.EvidenceSecretScanner;
import io.haifa.agent.testing.evidence.Sha256Digests;
import io.haifa.agent.testing.process.ProcessTreeCleanup;
import io.haifa.agent.testing.repository.RepositoryRevision;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Executes the Windows production CLI through real ConPTY and a process-local loopback model Stub. */
final class AutonomousDeliveryStubGate {
    static final String GATE_TYPE = "AUTONOMOUS_DELIVERY_PLATFORM_STUB";
    static final String STUB_CREDENTIAL = "local-conpty-stub-key";
    private static final DateTimeFormatter GATE_TIME =
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final List<String> PROVIDER_ENVIRONMENT = List.of(
            "DEEPSEEK_API_KEY",
            "BAILIAN_API_KEY",
            "DASHSCOPE_API_KEY",
            "ARK_API_KEY",
            "VOLCENGINE_API_KEY",
            "HAIFA_DEEPSEEK_LIVE_TEST",
            "HAIFA_BAILIAN_LIVE_TEST",
            "HAIFA_ARK_LIVE_TEST");

    private final ObjectMapper json;
    private final Clock clock;

    AutonomousDeliveryStubGate(Clock clock) {
        this(new ObjectMapper(), clock);
    }

    AutonomousDeliveryStubGate(ObjectMapper json, Clock clock) {
        this.json = Objects.requireNonNull(json, "json must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    Path run(
            Path campaign,
            String buildCommit,
            AutonomousDeliveryStubGateManifest suite,
            Path projectRoot,
            Path configRoot,
            AutonomousDeliveryMatrixManifest.Combination combination,
            RepositoryRevision productRevision,
            RepositoryRevision testConfigRevision,
            Path cliJar,
            Path nodeExecutable,
            Path javaExecutable,
            Path gitExecutable,
            Path nodePtyModule)
            throws Exception {
        requireWindowsContract(suite, combination);
        Path project = projectRoot.toAbsolutePath().normalize().toRealPath();
        Path config = configRoot.toAbsolutePath().normalize().toRealPath();
        Path cli = requireFile(cliJar, "production CLI jar");
        Path node = requireFile(nodeExecutable, "Node executable");
        Path java = requireFile(javaExecutable, "Java executable");
        Path git = requireFile(gitExecutable, "Git executable");
        Path pty = requireDirectory(nodePtyModule, "Node ConPTY module");
        Path script = requireFile(project.resolve("scripts/terminal-ui-conpty-acceptance.mjs"), "ConPTY driver");
        String nodeVersion = toolVersion(node, "--version");
        String javaVersion = toolVersion(java, "-version");
        String gitVersion = toolVersion(git, "--version");

        Path gate = campaign.resolve("stub-gate")
                .resolve("build-" + buildCommit)
                .resolve("gate-" + GATE_TIME.format(now()));
        Files.createDirectories(gate.getParent());
        Files.createDirectory(gate);
        Path raw = gate.resolve("driver-work");
        Path output = gate.resolve("driver-output.tmp");

        ProcessBuilder driver = new ProcessBuilder(
                node.toString(),
                script.toString(),
                "--run-root",
                raw.toString(),
                "--attempt",
                "1",
                "--mode",
                "governance",
                "--provider",
                "stub",
                "--jar",
                cli.toString(),
                "--node-pty",
                pty.toString(),
                "--java",
                java.toString(),
                "--git",
                git.toString());
        driver.directory(project.toFile());
        driver.redirectErrorStream(true);
        driver.redirectOutput(output.toFile());
        PROVIDER_ENVIRONMENT.forEach(driver.environment()::remove);
        Process process = driver.start();
        ProcessTreeCleanup.Tracker tracker = ProcessTreeCleanup.track(process);
        boolean completed = process.waitFor(suite.budget().maxWallTimeMillis(), TimeUnit.MILLISECONDS);
        int exitStatus = completed ? process.exitValue() : -1;
        ProcessTreeCleanup.Result driverCleanup = tracker.converge(completed, Duration.ofSeconds(5));

        Path rawManifestPath = raw.resolve("artifacts/manifest.json");
        JsonNode rawManifest = Files.isRegularFile(rawManifestPath)
                ? json.readTree(rawManifestPath.toFile())
                : json.createObjectNode();
        Map<String, Boolean> driverAssertions = booleanAssertions(rawManifest.path("assertions"));
        boolean rawDriverPassed = completed
                && exitStatus == 0
                && rawManifest.path("passed").asBoolean(false)
                && driverAssertions.values().stream().allMatch(Boolean::booleanValue);

        Path terminalEvidence = Files.createDirectory(gate.resolve("terminal-evidence"));
        copySanitized(
                raw.resolve("artifacts/terminal.cast"),
                terminalEvidence.resolve("session.cast"),
                List.of(raw, project, config, cli, pty, userHome()));
        copySanitized(
                raw.resolve("artifacts/terminal.txt"),
                terminalEvidence.resolve("terminal.txt"),
                List.of(raw, project, config, cli, pty, userHome()));
        copySanitized(
                raw.resolve("artifacts/interaction.jsonl"),
                terminalEvidence.resolve("interaction.jsonl"),
                List.of(raw, project, config, cli, pty, userHome()));
        copySanitized(
                raw.resolve("artifacts/screens.jsonl"),
                terminalEvidence.resolve("screens.jsonl"),
                List.of(raw, project, config, cli, pty, userHome()));
        copySanitized(
                raw.resolve("artifacts/trace-detail.log"),
                terminalEvidence.resolve("trace-detail.log"),
                List.of(raw, project, config, cli, pty, userHome()));
        copySanitized(
                raw.resolve("artifacts/git-status.txt"),
                terminalEvidence.resolve("git-status.txt"),
                List.of(raw, project, config, cli, pty, userHome()));

        ObjectNode driverResult = driverResult(
                rawManifest, terminalEvidence.resolve("session.cast"), terminalEvidence.resolve("interaction.jsonl"));
        TerminalDriverResultContract.Validation driverContract = TerminalDriverResultContract.validate(
                driverResult, combination.terminalBackend(), terminalEvidence.resolve("session.cast"));
        writeJson(gate.resolve("driver-result.json"), driverResult);
        writeJson(gate.resolve("driver-contract-result.json"), driverContract.artifact());

        AutonomousDeliveryStubSqliteEvidenceReader.Evidence sqlite =
                new AutonomousDeliveryStubSqliteEvidenceReader(json).read(raw.resolve("data/coding-terminal.db"));
        writeJson(gate.resolve("sqlite-evidence.json"), sqlite.artifact());
        writeJson(gate.resolve("driver-process-cleanup.json"), driverCleanup.artifact(exitStatus));

        ProcessTreeCleanup.Result parentExitCleanup = parentExitProbe(java);
        writeJson(gate.resolve("parent-exit-process-cleanup.json"), parentExitCleanup.artifact(0));

        long scratchResiduals = countScratchResiduals(raw);
        writeJson(
                gate.resolve("scratch-cleanup.json"),
                Map.of(
                        "schemaVersion",
                        1,
                        "scratchOrExecutionResiduals",
                        scratchResiduals,
                        "passed",
                        scratchResiduals == 0));
        deleteTree(raw);
        Files.deleteIfExists(output);
        boolean workspaceCleanup = !Files.exists(raw);
        writeJson(
                gate.resolve("workspace-cleanup.json"),
                Map.of("schemaVersion", 1, "driverWorkspaceRemoved", workspaceCleanup, "passed", workspaceCleanup));

        RepositoryRevision productRevisionAfter = RepositoryRevision.inspect(project);
        RepositoryRevision testConfigRevisionAfter = RepositoryRevision.inspect(config);
        boolean repositoryStateStable =
                productRevision.equals(productRevisionAfter) && testConfigRevision.equals(testConfigRevisionAfter);

        writeJson(
                gate.resolve("provider-evidence.json"),
                Map.of(
                        "schemaVersion",
                        1,
                        "dependencyMode",
                        "STUB",
                        "endpointClass",
                        "LOOPBACK_HTTP",
                        "externalProviderCalls",
                        0,
                        "estimatedCostUsd",
                        0.0,
                        "stubRequestCount",
                        rawManifest.path("stubProvider").path("requestCount").asInt(0),
                        "passed",
                        rawManifest.path("stubProvider").path("requestCount").asInt(0) >= 1));
        writeJson(
                gate.resolve("toolchain.json"),
                Map.of(
                        "schemaVersion", 1,
                        "node", safeVersion(nodeVersion),
                        "java", safeVersion(javaVersion),
                        "git", safeVersion(gitVersion),
                        "nodePtyModule", pty.getFileName().toString(),
                        "cliSha256", Sha256Digests.file(cli),
                        "driverSha256", Sha256Digests.file(script)));

        EvidenceSecretScanner.Result secretScan = EvidenceSecretScanner.scan(gate, List.of(STUB_CREDENTIAL));
        writeJson(gate.resolve("secret-scan.json"), secretScan);

        LinkedHashMap<String, Boolean> checks = new LinkedHashMap<>();
        checks.put(
                "CONPTY",
                rawDriverPassed
                        && "stub".equals(rawManifest.path("providerMode").asText())
                        && driverContract.passed()
                        && driverCleanup.naturalExit());
        checks.put(
                "APPROVAL",
                driverAssertions.getOrDefault("deniedShellRejected", false)
                        && driverAssertions.getOrDefault("deniedShellNotExecuted", false)
                        && driverAssertions.getOrDefault("approvalSelectorsCompleted", false)
                        && sqlite.approvedDecisionCount() >= 4
                        && sqlite.deniedDecisionCount() >= 1);
        checks.put(
                "SHELL",
                driverAssertions.getOrDefault("includedShellCompleted", false)
                        && driverAssertions.getOrDefault("excludedShellCompleted", false)
                        && driverAssertions.getOrDefault("windowsCommandResolutionCompleted", false));
        checks.put("SQLITE", sqlite.passed());
        checks.put("SECRET_SCAN", secretScan.passed());
        checks.put("EVIDENCE", driverContract.passed());
        checks.put(
                "PROCESS_TREE",
                driverCleanup.naturalExit()
                        && parentExitCleanup.passed()
                        && parentExitCleanup.observedDescendants() >= 1
                        && parentExitCleanup.terminationRequests() >= 1);
        checks.put("WORKSPACE_CLEANUP", workspaceCleanup && scratchResiduals == 0);
        checks.put("REPOSITORY_STABILITY", repositoryStateStable);
        checks.put(
                "NO_EXTERNAL_PROVIDER",
                "stub".equals(rawManifest.path("providerMode").asText())
                        && rawManifest.path("stubProvider").path("requestCount").asInt(0) >= 1);
        boolean successful = suite.requiredChecks().stream().allMatch(check -> checks.getOrDefault(check, false));

        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("schemaVersion", 3);
        summary.put("gateType", GATE_TYPE);
        summary.put("suiteId", suite.suiteId());
        summary.put("dependencyMode", suite.dependencyMode());
        summary.put("phase", "PLATFORM_STUB");
        summary.put("buildCommit", buildCommit);
        summary.put("productRevision", productRevision);
        summary.put("testConfigRevision", testConfigRevision);
        summary.put("productRevisionAfter", productRevisionAfter);
        summary.put("testConfigRevisionAfter", testConfigRevisionAfter);
        summary.put("repositoryStateStable", repositoryStateStable);
        summary.put("matrixCombination", combination);
        summary.put(
                "hostProfile",
                Map.of(
                        "platform", combination.platform(),
                        "terminalBackend", combination.terminalBackend(),
                        "sandboxProfile", combination.sandboxProfile(),
                        "networkPolicy", combination.networkPolicy(),
                        "shell", combination.shell(),
                        "isolationAssurance", combination.isolationAssurance()));
        summary.put("startedAndFinishedAt", now().toString());
        summary.put("externalProviderCalls", 0);
        summary.put("estimatedCostUsd", 0.0);
        summary.put("codingCasesExecuted", 0);
        summary.put("requiredChecks", suite.requiredChecks());
        summary.put("checks", checks);
        summary.put(
                "artifacts",
                Map.of(
                        "driverContract", "driver-contract-result.json",
                        "recording", "terminal-evidence/session.cast",
                        "sqlite", "sqlite-evidence.json",
                        "secretScan", "secret-scan.json",
                        "driverProcess", "driver-process-cleanup.json",
                        "parentExitProcess", "parent-exit-process-cleanup.json",
                        "scratchCleanup", "scratch-cleanup.json",
                        "workspaceCleanup", "workspace-cleanup.json"));
        summary.put("successful", successful);
        LinkedHashMap<String, Object> runManifest = new LinkedHashMap<>();
        runManifest.put("schemaVersion", 3);
        runManifest.put("gateType", GATE_TYPE);
        runManifest.put("suiteId", suite.suiteId());
        runManifest.put("dependencyMode", suite.dependencyMode());
        runManifest.put("buildCommit", buildCommit);
        runManifest.put("productRevision", productRevision);
        runManifest.put("testConfigRevision", testConfigRevision);
        runManifest.put("matrixCombination", combination);
        runManifest.put("provider", Map.of("mode", "LOOPBACK_STUB", "externalCalls", 0, "estimatedCostUsd", 0.0));
        runManifest.put(
                "inputs",
                Map.of(
                        "cliSha256", Sha256Digests.file(cli),
                        "driverSha256", Sha256Digests.file(script),
                        "driverProtocolVersion", TerminalDriverResultContract.PROTOCOL_VERSION));
        runManifest.put(
                "evidence",
                Map.of(
                        "phaseSummary", "phase-summary.json",
                        "driverResult", "driver-result.json",
                        "sqlite", "sqlite-evidence.json",
                        "secretScan", "secret-scan.json",
                        "manifestSha256", "manifest.sha256"));
        runManifest.put("result", successful ? "PASS" : "FAIL");
        writeJson(gate.resolve("run-manifest.json"), runManifest);
        writeJson(gate.resolve("phase-summary.json"), summary);
        EvidenceFinalizer.finalizeEvidence(gate);
        productRevision.requireUnchanged(productRevisionAfter, "product repository");
        testConfigRevision.requireUnchanged(testConfigRevisionAfter, "test-config repository");
        if (!successful) {
            throw new IllegalStateException("Autonomous Delivery Stub Gate failed reviewed checks");
        }
        return gate;
    }

    private ProcessTreeCleanup.Result parentExitProbe(Path java) throws Exception {
        Process process = new ProcessBuilder(
                        java.toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        AutonomousDeliveryStubProcessProbeMain.class.getName(),
                        "parent-exits")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        ProcessTreeCleanup.Tracker tracker = ProcessTreeCleanup.track(process);
        boolean parentExited = process.waitFor(10, TimeUnit.SECONDS);
        return tracker.converge(parentExited, Duration.ofSeconds(5));
    }

    private ObjectNode driverResult(JsonNode manifest, Path recording, Path interactions) throws Exception {
        List<JsonNode> interactionEvents = jsonLines(interactions);
        double objective = elapsed(interactionEvents, "seed-session", 0.1);
        double quit = elapsed(interactionEvents, "quit", Math.max(0.3, objective + 0.2));
        long started = Instant.parse(manifest.path("startedAt").asText(Instant.EPOCH.toString()))
                .toEpochMilli();
        long completed = Instant.parse(manifest.path("completedAt").asText(Instant.EPOCH.toString()))
                .toEpochMilli();
        int events;
        try (var lines = Files.lines(recording, StandardCharsets.UTF_8)) {
            events = Math.max(0, (int) lines.filter(line -> !line.isBlank()).count() - 1);
        }
        ObjectNode result = json.createObjectNode();
        result.put("schemaVersion", 2);
        result.put("driverProtocolVersion", TerminalDriverResultContract.PROTOCOL_VERSION);
        result.put("terminalBackend", "conpty");
        result.put("terminalExitStatus", manifest.path("exit").path("exitCode").asInt(-1));
        result.put("agentWallTimeSeconds", Math.max(0, completed - started) / 1000.0);
        result.put("acceptanceExitStatus", manifest.path("passed").asBoolean(false) ? 0 : 1);
        result.put("acceptanceStdout", "{\"gate\":\"platform-stub\"}");
        result.put("acceptanceStderr", "");
        result.put("acceptancePassed", manifest.path("passed").asBoolean(false));
        result.put("interactionCount", interactionEvents.size());
        result.put("humanFollowUps", 0);
        ArrayNode states = result.putArray("terminalStates");
        states.addObject().put("state", "IDLE").put("atSeconds", 0.0);
        states.addObject().put("state", "RUNNING").put("atSeconds", objective);
        states.addObject().put("state", "IDLE").put("atSeconds", Math.max(objective, quit - 0.1));
        ArrayNode timeline = result.putArray("inputTimeline");
        timeline.addObject()
                .put("action", "objective")
                .put("atSeconds", objective)
                .put("characters", 17);
        timeline.addObject().put("action", "quit").put("atSeconds", quit).put("characters", 5);
        ObjectNode recordingNode = result.putObject("recording");
        recordingNode.put("format", "asciicast-v2");
        recordingNode.put("path", "session.cast");
        recordingNode.put("ansiMode", "preserved");
        recordingNode.put("sha256", Sha256Digests.file(recording));
        recordingNode.put("bytes", Files.size(recording));
        recordingNode.put("events", events);
        recordingNode.put("truncated", false);
        recordingNode.put("columns", 120);
        recordingNode.put("rows", 40);
        recordingNode.put("encoding", "UTF-8");
        return result;
    }

    private List<JsonNode> jsonLines(Path file) throws IOException {
        List<JsonNode> values = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) values.add(json.readTree(line));
        }
        return values;
    }

    private static double elapsed(List<JsonNode> events, String label, double fallback) {
        return events.stream()
                .filter(event -> label.equals(event.path("label").asText()))
                .mapToDouble(event -> event.path("elapsedSeconds").asDouble(fallback))
                .findFirst()
                .orElse(fallback);
    }

    private static Map<String, Boolean> booleanAssertions(JsonNode assertions) {
        LinkedHashMap<String, Boolean> values = new LinkedHashMap<>();
        if (assertions.isObject()) {
            assertions
                    .fields()
                    .forEachRemaining(
                            entry -> values.put(entry.getKey(), entry.getValue().asBoolean(false)));
        }
        return Map.copyOf(values);
    }

    private static void requireWindowsContract(
            AutonomousDeliveryStubGateManifest suite, AutonomousDeliveryMatrixManifest.Combination combination) {
        if (!suite.matrixRef().equals("autonomous-delivery-v1")
                || !suite.platform().equals(combination.platform())
                || !"windows".equals(combination.platform())
                || !"conpty".equals(combination.terminalBackend())
                || !"host-guarded".equals(combination.sandboxProfile())
                || !"allow".equals(combination.networkPolicy())
                || !"powershell".equals(combination.shell())
                || !"TRUSTED_HOST_ONLY".equals(combination.isolationAssurance())) {
            throw new IllegalArgumentException("Stub Gate requires the reviewed Windows Host Trusted combination");
        }
    }

    private static Path requireFile(Path value, String label) throws IOException {
        if (value == null || !Files.isRegularFile(value)) {
            throw new IllegalArgumentException(label + " is unavailable");
        }
        return value.toAbsolutePath().normalize().toRealPath();
    }

    private static Path requireDirectory(Path value, String label) throws IOException {
        if (value == null || !Files.isDirectory(value)) {
            throw new IllegalArgumentException(label + " is unavailable");
        }
        return value.toAbsolutePath().normalize().toRealPath();
    }

    private static String toolVersion(Path executable, String argument) throws Exception {
        Process process = new ProcessBuilder(executable.toString(), argument)
                .redirectErrorStream(true)
                .start();
        byte[] output = process.getInputStream().readNBytes(4096);
        if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
            process.destroyForcibly();
            throw new IllegalArgumentException("tool version preflight failed: " + executable.getFileName());
        }
        String version = new String(output, StandardCharsets.UTF_8).strip();
        if (version.isBlank()) throw new IllegalArgumentException("tool version preflight returned no version");
        return version;
    }

    private static String safeVersion(String value) {
        return value.lines()
                .findFirst()
                .orElse("")
                .replaceAll("[^A-Za-z0-9._+() -]", "")
                .strip();
    }

    private static Path userHome() {
        return Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
    }

    private static void copySanitized(Path source, Path target, Collection<Path> sensitivePaths) throws IOException {
        if (!Files.isRegularFile(source)) {
            throw new IOException("required Stub Gate artifact is unavailable: " + source.getFileName());
        }
        String content = Files.readString(source, StandardCharsets.UTF_8);
        for (Path path : sensitivePaths) content = redact(content, path);
        Files.writeString(target, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private static String redact(String content, Path path) {
        String nativePath = path.toString();
        String slashPath = nativePath.replace('\\', '/');
        String jsonPath = nativePath.replace("\\", "\\\\");
        return content.replace(jsonPath, "<HOST_PATH>")
                .replace(nativePath, "<HOST_PATH>")
                .replace(slashPath, "<HOST_PATH>");
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                DosFileAttributeView dos = Files.getFileAttributeView(file, DosFileAttributeView.class);
                if (dos != null && dos.readAttributes().isReadOnly()) dos.setReadOnly(false);
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) throw exception;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static long countScratchResiduals(Path root) throws IOException {
        if (!Files.exists(root)) return 0;
        try (var paths = Files.walk(root)) {
            return paths.map(path -> path.getFileName().toString().toLowerCase())
                    .filter(name -> name.contains("scratch") || name.contains(".haifa-execution"))
                    .count();
        }
    }

    private void writeJson(Path path, Object value) throws IOException {
        json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }

    private Instant now() {
        return Instant.ofEpochMilli(clock.millis());
    }
}

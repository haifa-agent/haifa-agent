package io.haifa.agent.testing.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.application.project.product.coding.client.CodingSessionClient;
import io.haifa.agent.cli.StandaloneCodingAgent;
import io.haifa.agent.cli.StandaloneCodingAgents;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.agent.testing.evidence.Sha256Digests;
import io.haifa.agent.testing.repository.RepositoryRevision;
import io.haifa.agent.testing.suite.ResolvedAgentProfile;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Executes one isolated Autonomous Delivery capability case through the standard product client. */
final class AutonomousDeliveryRepeatExecutor {
    private static final String EMPTY_DIGEST = "0".repeat(64);

    private final ObjectMapper json;
    private final Clock clock;
    private final AutonomousDeliveryPhaseThreeVerificationCollector phaseThreeVerificationCollector;
    private final PythonJsonAcceptanceGrader acceptanceGrader = new PythonJsonAcceptanceGrader();

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
            ResolvedAgentProfile agentProfile,
            DeliveryToolchainSet toolchains,
            DeliveryHostProfile hostProfile,
            AutonomousDeliveryMatrixManifest.Combination matrixCombination,
            RepositoryRevision productRevision,
            RepositoryRevision testConfigRevision,
            String executionPlanSha256,
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
        Path database = repeat.resolve("runtime.db");
        writeRunManifest(
                repeat,
                buildCommit,
                suite,
                testCase,
                repetition,
                agentProfile,
                toolchains,
                hostProfile,
                matrixCombination,
                productRevision,
                testConfigRevision,
                executionPlanSha256);

        long startedNanos = System.nanoTime();
        ClientOutcome client = runClient(
                workspace,
                Files.readString(immutableCase.resolve("prompt.txt"), StandardCharsets.UTF_8),
                agentProfile,
                database,
                transcripts,
                suite.budget().maxWallTimeMillis());
        long wallTimeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);

        String after = workspaceDigest(workspace);
        Files.writeString(repeat.resolve("workspace-after.sha256"), after + "\n", StandardOpenOption.CREATE_NEW);
        runGit(toolchains, workspace, repeat.resolve("workspace.diff"), "diff", "--binary", "--no-ext-diff");
        AutonomousDeliveryRuntimeEvidenceReader.Evidence authoritative = new AutonomousDeliveryRuntimeEvidenceReader(
                        json)
                .readOrUnavailable(database, !client.contract().runStarted());
        int iterations = maximumIteration(client.events());
        boolean withinBudget = authoritative.modelCalls() <= suite.budget().maxModelCalls()
                && authoritative.toolCalls() <= suite.budget().maxToolCalls()
                && iterations <= suite.budget().maxIterations()
                && wallTimeMillis <= suite.budget().maxWallTimeMillis();

        AutonomousDeliveryAcceptanceGrade grade = grade(
                testCase,
                immutableCase.resolve("acceptance.py"),
                workspace,
                toolchains.pythonExecutable(),
                remainingGraderBudget(suite, wallTimeMillis));
        Map<String, Object> acceptanceArtifact = acceptanceArtifact(testCase, grade);
        boolean bounded = client.contract().completedWithinBudget()
                && client.contract().assemblyClosed()
                && withinBudget
                && authoritative.maximumClusterAttempts() <= 4;
        boolean caseTenConverged = testCase.caseId().equals("10") && bounded && wallTimeMillis < 900_000;
        boolean preliminaryGatePassed = gateEligible(
                grade.passed(),
                client.contract().passed(),
                authoritative.scratchSatisfied(),
                authoritative.terminalStateObserved(),
                bounded);
        AutonomousDeliveryPhaseThreeVerificationCollector.Result phaseThree = phasePolicy.requiresExternalVerification()
                ? phaseThreeVerificationCollector.collect(
                        repeat,
                        new AutonomousDeliveryPhaseThreeVerificationCollector.Input(
                                AutonomousDeliveryRepeatEvidenceCollector.CaseMetadata.from(testCase),
                                grade.passed(),
                                grade.checks(),
                                grade.failures(),
                                before,
                                after,
                                authoritative.scratchSatisfied(),
                                client.contract().assemblyClosed()))
                : AutonomousDeliveryPhaseThreeVerificationCollector.notRequired();
        preliminaryGatePassed &= phaseThree.passed();

        writeJson(repeat.resolve("public-client-evidence.json"), publicEvidence(client.events()));
        AutonomousDeliveryRepeatEvidenceCollector.Result collected = new AutonomousDeliveryRepeatEvidenceCollector(json)
                .collect(
                        repeat,
                        new AutonomousDeliveryRepeatEvidenceCollector.Input(
                                AutonomousDeliveryRepeatEvidenceCollector.CaseMetadata.from(testCase),
                                repetition,
                                client.contract(),
                                wallTimeMillis / 1000.0,
                                wallTimeMillis,
                                grade.passed(),
                                acceptanceArtifact,
                                bounded,
                                caseTenConverged,
                                preliminaryGatePassed,
                                authoritative,
                                iterations,
                                withinBudget,
                                !before.equals(after),
                                phaseThree.passed(),
                                phaseThree.atomicity()),
                        selectedSecrets);
        return collected.summary();
    }

    static boolean gateEligible(
            boolean acceptancePassed,
            boolean clientContractPassed,
            boolean scratchSatisfied,
            boolean terminalStateObserved,
            boolean bounded) {
        return acceptancePassed && clientContractPassed && scratchSatisfied && terminalStateObserved && bounded;
    }

    private ClientOutcome runClient(
            Path workspace,
            String prompt,
            ResolvedAgentProfile profile,
            Path database,
            Path transcripts,
            long maxWallTimeMillis) {
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put("HAIFA_PERSISTENCE_MODE", "SQLITE_WITH_JSONL");
        environment.put("HAIFA_SQLITE_DATABASE_PATH", database.toString());
        environment.put("HAIFA_TRANSCRIPT_ROOT", transcripts.toString());
        boolean assemblyOpened = false;
        boolean runStarted = false;
        boolean terminalObserved = false;
        boolean completedWithinBudget = false;
        boolean assemblyClosed = false;
        String terminalStatus = "NOT_STARTED";
        String productAssemblyDigest = EMPTY_DIGEST;
        List<AgentRunEvent> events = List.of();
        List<String> failures = new ArrayList<>();
        StandaloneCodingAgent agent = null;
        try {
            agent = StandaloneCodingAgents.open(workspace, profile.configurationPath(), environment);
            assemblyOpened = true;
            productAssemblyDigest = agent.metadata().assemblyDigest();
            CodingSessionClient client = agent.client();
            var created = client.create(agent.projectId(), prompt, "autonomous-delivery-" + UUID.randomUUID());
            var sessionId = created.summary().sessionId();
            AgentRunSnapshot snapshot = created.activeRun().orElseThrow();
            runStarted = true;
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxWallTimeMillis);
            while (!snapshot.status().isTerminal() && System.nanoTime() < deadline) {
                var pending = client.pendingInteraction(snapshot.runId());
                if (pending.isPresent()) {
                    client.respond(
                            pending.orElseThrow(),
                            InteractionAction.APPROVE,
                            "autonomous-delivery-approve-" + UUID.randomUUID());
                }
                Thread.sleep(25);
                snapshot = client.findRun(snapshot.runId()).orElseThrow();
            }
            completedWithinBudget = snapshot.status().isTerminal();
            if (!completedWithinBudget) {
                client.cancel(sessionId, "autonomous-delivery-timeout-" + UUID.randomUUID());
                failures.add("CLIENT_TIMEOUT");
            }
            terminalObserved = snapshot.status().isTerminal();
            terminalStatus = snapshot.status().name();
            events = readAllEvents(client, snapshot);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            failures.add("CLIENT_INTERRUPTED");
        } catch (RuntimeException exception) {
            failures.add(assemblyOpened ? "CLIENT_EXECUTION_FAILED" : "CLIENT_ASSEMBLY_FAILED");
        } finally {
            if (agent != null) {
                try {
                    agent.close();
                    assemblyClosed = true;
                } catch (RuntimeException exception) {
                    failures.add("CLIENT_CLOSE_FAILED");
                }
            }
        }
        CodingClientExecutionContract contract = new CodingClientExecutionContract(
                assemblyOpened,
                runStarted,
                terminalObserved,
                completedWithinBudget,
                assemblyClosed,
                terminalStatus,
                events.size(),
                profile.agentAssemblyDigest(),
                productAssemblyDigest,
                List.copyOf(failures));
        return new ClientOutcome(contract, events);
    }

    private static List<AgentRunEvent> readAllEvents(CodingSessionClient client, AgentRunSnapshot snapshot) {
        ArrayList<AgentRunEvent> events = new ArrayList<>();
        RunEventCursor cursor = RunEventCursor.beforeFirst(snapshot.runId());
        boolean more;
        do {
            var page = client.events(snapshot.runId(), cursor, 100);
            events.addAll(page.items());
            cursor = page.nextCursor();
            more = page.hasMore();
        } while (more);
        return List.copyOf(events);
    }

    private static int maximumIteration(List<AgentRunEvent> events) {
        return events.stream()
                .map(AgentRunEvent::payload)
                .filter(RunEventPayloads.ModelLifecycle.class::isInstance)
                .map(RunEventPayloads.ModelLifecycle.class::cast)
                .mapToInt(RunEventPayloads.ModelLifecycle::iteration)
                .max()
                .orElse(0);
    }

    private AutonomousDeliveryAcceptanceGrade grade(
            AutonomousDeliveryCase testCase, Path acceptance, Path workspace, Path python, Duration timeout) {
        try {
            return acceptanceGrader.grade(testCase, acceptance, workspace, python, timeout);
        } catch (IOException exception) {
            return new AutonomousDeliveryAcceptanceGrade(
                    testCase.graderId(),
                    testCase.caseId(),
                    false,
                    -1,
                    Map.of("graderOutputValid", false),
                    List.of("GRADER_OUTPUT_INVALID"),
                    0);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new AutonomousDeliveryAcceptanceGrade(
                    testCase.graderId(),
                    testCase.caseId(),
                    false,
                    -1,
                    Map.of("graderCompleted", false),
                    List.of("GRADER_INTERRUPTED"),
                    0);
        }
    }

    private static Duration remainingGraderBudget(AutonomousDeliverySuiteManifest suite, long elapsedMillis) {
        long remaining = Math.max(1_000, suite.budget().maxWallTimeMillis() - elapsedMillis);
        return Duration.ofMillis(Math.min(remaining, Duration.ofMinutes(15).toMillis()));
    }

    private static Map<String, Object> acceptanceArtifact(
            AutonomousDeliveryCase testCase, AutonomousDeliveryAcceptanceGrade grade) {
        LinkedHashMap<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("schemaVersion", 2);
        artifact.put("caseId", testCase.caseId());
        artifact.put("caseVersion", testCase.caseVersion());
        artifact.put("graderId", grade.graderId());
        artifact.put("passed", grade.passed());
        artifact.put("exitCode", grade.exitCode());
        artifact.put("checks", grade.checks());
        artifact.put("failures", grade.failures());
        artifact.put("durationMillis", grade.durationMillis());
        return Map.copyOf(artifact);
    }

    private static Map<String, Object> publicEvidence(List<AgentRunEvent> events) {
        long modelCalls = events.stream()
                .map(AgentRunEvent::payload)
                .filter(RunEventPayloads.ModelLifecycle.class::isInstance)
                .map(RunEventPayloads.ModelLifecycle.class::cast)
                .filter(event -> event.status().equals("SUCCEEDED"))
                .count();
        long toolCalls = events.stream()
                .map(AgentRunEvent::payload)
                .filter(RunEventPayloads.ToolLifecycle.class::isInstance)
                .map(RunEventPayloads.ToolLifecycle.class::cast)
                .filter(event -> event.status().equals("SUCCEEDED"))
                .count();
        return Map.of(
                "schemaVersion",
                1,
                "eventCount",
                events.size(),
                "maximumIteration",
                maximumIteration(events),
                "succeededModelCalls",
                modelCalls,
                "succeededToolCalls",
                toolCalls);
    }

    private void writeRunManifest(
            Path repeat,
            String buildCommit,
            AutonomousDeliverySuiteManifest suite,
            AutonomousDeliveryCase testCase,
            int repetition,
            ResolvedAgentProfile agentProfile,
            DeliveryToolchainSet toolchains,
            DeliveryHostProfile hostProfile,
            AutonomousDeliveryMatrixManifest.Combination matrixCombination,
            RepositoryRevision productRevision,
            RepositoryRevision testConfigRevision,
            String executionPlanSha256)
            throws IOException {
        LinkedHashMap<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 4);
        manifest.put("phase", suite.phase());
        manifest.put("suiteId", suite.suiteId());
        manifest.put("matrixRef", suite.matrixRef());
        manifest.put("matrixCombination", matrixCombination);
        manifest.put("agentProfile", agentProfile.manifest());
        manifest.put("agentAssemblyDigest", agentProfile.agentAssemblyDigest());
        manifest.put("buildCommit", buildCommit);
        manifest.put("productRevision", productRevision);
        manifest.put("testConfigRevision", testConfigRevision);
        manifest.put("executionPlanSha256", executionPlanSha256);
        manifest.put("caseId", testCase.caseId());
        manifest.put("caseVersion", testCase.caseVersion());
        manifest.put("repetition", repetition);
        manifest.put("startedAt", now().toString());
        manifest.put("platform", hostProfile.platform());
        manifest.put("sandboxProfile", hostProfile.executionProvider());
        manifest.put("networkPolicy", hostProfile.networkPolicy());
        manifest.put("shell", hostProfile.shell());
        manifest.put("isolationAssurance", hostProfile.isolationAssurance());
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
        builder.redirectOutput(
                output == null ? ProcessBuilder.Redirect.DISCARD : ProcessBuilder.Redirect.to(output.toFile()));
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

    private void writeJson(Path path, Object value) throws IOException {
        json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }

    private record ClientOutcome(CodingClientExecutionContract contract, List<AgentRunEvent> events) {
        private ClientOutcome {
            Objects.requireNonNull(contract, "contract must not be null");
            events = List.copyOf(events);
        }
    }
}

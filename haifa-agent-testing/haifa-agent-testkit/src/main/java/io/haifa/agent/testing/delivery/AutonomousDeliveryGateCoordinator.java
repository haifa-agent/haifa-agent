package io.haifa.agent.testing.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.testing.authorization.SecretPreflight;
import io.haifa.agent.testing.evidence.EvidenceFinalizer;
import io.haifa.agent.testing.repository.RepositoryRevision;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Coordinates serial, repository-external Autonomous Delivery Phase 1/2/3 gates. */
final class AutonomousDeliveryGateCoordinator {
    private static final DateTimeFormatter GATE_TIME =
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final String POSIX_DRIVER_RESOURCE = "autonomous-delivery/run_terminal.py";
    private static final String WINDOWS_DRIVER_RESOURCE = "autonomous-delivery/run_terminal.mjs";

    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock;
    private final AutonomousDeliveryDeterministicProbeExecutor deterministicProbeExecutor =
            new AutonomousDeliveryDeterministicProbeExecutor();
    private final AutonomousDeliveryRepeatExecutor repeatExecutor;

    AutonomousDeliveryGateCoordinator(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.repeatExecutor = new AutonomousDeliveryRepeatExecutor(json, clock);
    }

    Path run(
            Path campaign,
            String buildCommit,
            AutonomousDeliverySuiteManifest suite,
            AutonomousDeliveryCaseCatalog catalog,
            Path cliJar,
            Map<String, Path> executablePaths,
            DeliveryHostProfile hostProfile,
            Path projectRoot,
            Path configRoot,
            AutonomousDeliveryMatrixManifest.Combination matrixCombination,
            RepositoryRevision productRevision,
            RepositoryRevision testConfigRevision)
            throws Exception {
        AutonomousDeliveryPhasePolicy phasePolicy = AutonomousDeliveryPhasePolicy.resolve(suite);
        int phaseNumber = phasePolicy.phaseNumber();
        Path jar = requireFile(cliJar, "CLI JAR");
        DeliveryToolchainSet toolchains = DeliveryToolchainSet.validate(executablePaths);
        validateHostProfile(hostProfile, matrixCombination);
        SecretPreflight.ResolvedSecrets selectedSecrets =
                SecretPreflight.require(System.getenv(), List.of("DEEPSEEK_API_KEY", "HAIFA_CONTINUATION_KEY"));

        Path gate = campaign.resolve("phase-" + phaseNumber)
                .resolve("build-" + buildCommit)
                .resolve("gate-" + GATE_TIME.format(now()));
        Files.createDirectories(gate.getParent());
        Files.createDirectory(gate);
        boolean nodeDriver = hostProfile.platform().equals("windows");
        Path driver = gate.resolve(nodeDriver ? "terminal-driver.mjs" : "terminal-driver.py");
        copyResource(nodeDriver ? WINDOWS_DRIVER_RESOURCE : POSIX_DRIVER_RESOURCE, driver);

        Map<String, Object> deterministicAnalyze = phasePolicy.requiresDeterministicAnalyze()
                ? deterministicProbeExecutor.execute(
                        gate,
                        projectRoot,
                        hostProfile,
                        AutonomousDeliveryDeterministicProbeExecutor.ProbeDefinition.READ_ONLY_ANALYZE)
                : Map.of("required", false);
        Map<String, Object> deterministicReplay = phasePolicy.requiresDeterministicReplay()
                ? deterministicProbeExecutor.execute(
                        gate,
                        projectRoot,
                        hostProfile,
                        AutonomousDeliveryDeterministicProbeExecutor.ProbeDefinition.TRACE_REPLAY)
                : Map.of("required", false);

        boolean successful = phasePolicy.prerequisiteEvidencePassed(deterministicAnalyze, deterministicReplay);
        List<Map<String, Object>> results = new ArrayList<>();
        for (AutonomousDeliverySuiteManifest.CaseSelection selection : suite.cases()) {
            AutonomousDeliveryCase testCase = catalog.require(selection.caseId());
            for (int repetition = 1; repetition <= selection.repetitions(); repetition++) {
                Path repeat = gate.resolve("case-" + testCase.caseId()).resolve("repeat-%02d".formatted(repetition));
                Map<String, Object> result = repeatExecutor.execute(
                        repeat,
                        buildCommit,
                        suite,
                        testCase,
                        repetition,
                        jar,
                        toolchains,
                        hostProfile,
                        matrixCombination,
                        productRevision,
                        testConfigRevision,
                        driver,
                        nodeDriver,
                        phasePolicy,
                        selectedSecrets.values());
                results.add(result);
                if (selection.blocking() && !Boolean.TRUE.equals(result.get("gatePassed"))) {
                    successful = false;
                }
            }
        }
        Files.delete(driver);

        RepositoryRevision productRevisionAfter = RepositoryRevision.inspect(projectRoot);
        RepositoryRevision testConfigRevisionAfter = RepositoryRevision.inspect(configRoot);
        AutonomousDeliveryGateResultAggregator.Aggregation aggregation =
                AutonomousDeliveryGateResultAggregator.aggregate(
                        suite,
                        matrixCombination,
                        buildCommit,
                        productRevision,
                        testConfigRevision,
                        productRevisionAfter,
                        testConfigRevisionAfter,
                        now(),
                        successful,
                        deterministicAnalyze,
                        deterministicReplay,
                        results);
        Map<String, Object> summary = aggregation.summary();
        writeJson(gate.resolve("phase-summary.json"), summary);
        writeJson(
                gate.resolve("result-projection-v1.json"),
                AutonomousDeliveryResultProjection.batch(
                        suite, matrixCombination, productRevision, testConfigRevision, now(), results));
        writeBaselineComparison(campaign, gate, buildCommit, summary, phaseNumber);
        EvidenceFinalizer.finalizeEvidence(gate);
        if (!aggregation.successful()) {
            throw new IllegalStateException("Phase " + phaseNumber + " gate failed; immutable evidence: " + gate);
        }
        return gate;
    }

    private static void validateHostProfile(
            DeliveryHostProfile hostProfile, AutonomousDeliveryMatrixManifest.Combination matrixCombination) {
        if (!hostProfile.terminalDriverSupported()) {
            throw new IllegalArgumentException(hostProfile.id() + " has no compatible terminal driver");
        }
        if (!matrixCombination.hostProfile().equals(hostProfile.id())) {
            throw new IllegalArgumentException("matrix combination and DeliveryHostProfile do not match");
        }
        if (hostProfile.platform().equals("windows")) {
            requireEnvironmentValue("HAIFA_NODE_PTY_MODULE");
        }
    }

    private void writeBaselineComparison(
            Path campaign, Path gate, String buildCommit, Map<String, Object> summary, int phaseNumber)
            throws IOException {
        Path baselineIndex = campaign.resolve("baseline").resolve("historical-evidence-index.json");
        JsonNode baselines = json.readTree(baselineIndex.toFile());
        LinkedHashMap<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("schemaVersion", 1);
        comparison.put("comparisonType", "PHASE_" + phaseNumber + "_GATE_VS_READ_ONLY_HISTORICAL_EVIDENCE");
        comparison.put("buildCommit", buildCommit);
        comparison.put("gateEvidence", campaign.relativize(gate).toString().replace('\\', '/'));
        comparison.put("generatedAt", now().toString());
        comparison.put("historicalEvidence", baselines);
        comparison.put(
                "interpretation",
                "Historical entries are integrity-pinned evidence references; Phase " + phaseNumber
                        + " outcomes are not "
                        + "treated as performance-equivalent unless case and harness versions match.");
        comparison.put("phaseOutcome", summary);
        Path output = campaign.resolve("comparison")
                .resolve("phase-" + phaseNumber + "-build-" + buildCommit + "-" + gate.getFileName()
                        + "-vs-baseline.json");
        if (Files.exists(output)) {
            throw new IOException("Phase " + phaseNumber + " baseline comparison already exists");
        }
        writeJson(output, comparison);
    }

    private Instant now() {
        return Instant.ofEpochMilli(clock.millis());
    }

    private static Path requireFile(Path value, String label) throws IOException {
        if (value == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        Path file = value.toAbsolutePath().normalize().toRealPath();
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException(label + " must be a regular file");
        }
        return file;
    }

    private static String requireEnvironmentValue(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be present in the process environment");
        }
        return value;
    }

    private static void copyResource(String name, Path destination) throws IOException {
        try (InputStream input =
                AutonomousDeliveryGateCoordinator.class.getClassLoader().getResourceAsStream(name)) {
            if (input == null) {
                throw new IOException("Gate driver resource is unavailable");
            }
            Files.copy(input, destination);
        }
    }

    private void writeJson(Path path, Object value) throws IOException {
        json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }
}

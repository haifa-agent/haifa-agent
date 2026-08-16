package io.haifa.agent.testing.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.application.project.product.coding.client.CodingAgentClientFactory;
import io.haifa.agent.testing.authorization.SecretPreflight;
import io.haifa.agent.testing.evidence.EvidenceFinalizer;
import io.haifa.agent.testing.evidence.EvidenceSecretScanner;
import io.haifa.agent.testing.harness.PlatformManifest;
import io.haifa.agent.testing.harness.ResolvedTestPlan;
import io.haifa.agent.testing.repository.RepositoryRevision;
import io.haifa.agent.testing.suite.ResolvedAgentProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Coordinates serial, repository-external Autonomous Delivery Phase 1/2/3 gates. */
final class AutonomousDeliveryGateCoordinator {
    private static final DateTimeFormatter GATE_TIME =
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock;
    private final AutonomousDeliveryDeterministicProbeExecutor deterministicProbeExecutor =
            new AutonomousDeliveryDeterministicProbeExecutor();
    private final AutonomousDeliveryRepeatExecutor repeatExecutor;

    AutonomousDeliveryGateCoordinator(Clock clock, CodingAgentClientFactory clientFactory) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.repeatExecutor = new AutonomousDeliveryRepeatExecutor(json, clock, clientFactory);
    }

    Path run(
            Path campaign,
            String buildCommit,
            AutonomousDeliverySuiteManifest suite,
            AutonomousDeliveryCaseCatalog catalog,
            ResolvedAgentProfile agentProfile,
            Map<String, Path> executablePaths,
            DeliveryHostProfile hostProfile,
            Path projectRoot,
            Path configRoot,
            PlatformManifest.PlatformProfile matrixCombination,
            RepositoryRevision productRevision,
            RepositoryRevision testConfigRevision,
            ResolvedTestPlan executionPlan,
            long approvedMaxCostMinorUnits)
            throws Exception {
        Objects.requireNonNull(executionPlan, "executionPlan must not be null");
        AutonomousDeliveryPhasePolicy phasePolicy = AutonomousDeliveryPhasePolicy.resolve(suite);
        int phaseNumber = phasePolicy.phaseNumber();
        DeliveryToolchainSet toolchains = DeliveryToolchainSet.validate(executablePaths);
        validateHostProfile(hostProfile, matrixCombination);
        AutonomousDeliveryLiveBudget.Authorization liveBudget =
                AutonomousDeliveryLiveBudget.authorize(suite, approvedMaxCostMinorUnits);
        SecretPreflight.ResolvedSecrets requiredEnvironment =
                SecretPreflight.require(System.getenv(), agentProfile.requiredEnvironmentNames());
        List<String> selectedSecrets = agentProfile.credentialEnvironmentNames().stream()
                .map(requiredEnvironment::value)
                .toList();

        Path gate = campaign.resolve("phase-" + phaseNumber)
                .resolve("build-" + buildCommit)
                .resolve("gate-" + GATE_TIME.format(now()));
        Files.createDirectories(gate.getParent());
        Files.createDirectory(gate);
        writeJson(gate.resolve("execution-plan.json"), executionPlan.artifact());
        writeJson(gate.resolve("live-budget-authorization.json"), liveBudget.artifact());
        long batchStartedNanos = System.nanoTime();
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
                        agentProfile,
                        toolchains,
                        hostProfile,
                        matrixCombination,
                        productRevision,
                        testConfigRevision,
                        executionPlan.sha256(),
                        phasePolicy,
                        selectedSecrets);
                results.add(result);
                if (selection.blocking() && !Boolean.TRUE.equals(result.get("gatePassed"))) {
                    successful = false;
                }
            }
        }
        RepositoryRevision productRevisionAfter = RepositoryRevision.inspect(projectRoot);
        RepositoryRevision testConfigRevisionAfter = RepositoryRevision.inspect(configRoot);
        long batchElapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - batchStartedNanos);
        AutonomousDeliveryLiveBudget.Evidence liveBudgetEvidence =
                AutonomousDeliveryLiveBudget.evidence(suite, liveBudget, results, batchElapsedMillis);
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
                        results,
                        executionPlan,
                        liveBudgetEvidence);
        Map<String, Object> summary = aggregation.summary();
        java.util.LinkedHashMap<String, Object> runResult = new java.util.LinkedHashMap<>(summary);
        runResult.put("schemaVersion", 1);
        runResult.put("nativeStatus", aggregation.successful() ? "GATE_PASSED" : "GATE_FAILED");
        runResult.put("status", aggregation.successful() ? "PASSED" : "FAILED");
        runResult.put("failureClassification", aggregation.successful() ? "NONE" : "ACCEPTANCE_FAILED");
        runResult.put("liveBudget", liveBudgetEvidence.artifact());
        runResult.put("attachments", List.of());
        writeJson(gate.resolve("run-result.json"), runResult);
        writeJson(gate.resolve("secret-scan.json"), EvidenceSecretScanner.scan(gate, selectedSecrets));
        EvidenceFinalizer.finalizeEvidence(gate);
        if (!aggregation.successful()) {
            throw new IllegalStateException("Phase " + phaseNumber + " gate failed; immutable evidence: " + gate);
        }
        return gate;
    }

    private static void validateHostProfile(
            DeliveryHostProfile hostProfile, PlatformManifest.PlatformProfile matrixCombination) {
        if (!matrixCombination.hostProfile().equals(hostProfile.id())) {
            throw new IllegalArgumentException("matrix combination and DeliveryHostProfile do not match");
        }
    }

    private Instant now() {
        return Instant.ofEpochMilli(clock.millis());
    }

    private void writeJson(Path path, Object value) throws java.io.IOException {
        json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }
}

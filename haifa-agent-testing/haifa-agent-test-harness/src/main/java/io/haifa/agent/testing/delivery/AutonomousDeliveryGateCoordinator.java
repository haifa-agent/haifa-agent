package io.haifa.agent.testing.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.application.project.product.coding.client.CodingAgentClientFactory;
import io.haifa.agent.testing.authorization.SecretPreflight;
import io.haifa.agent.testing.harness.PlatformManifest;
import io.haifa.agent.testing.harness.ResolvedRunContext;
import io.haifa.agent.testing.harness.RunEvidenceWriter;
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
import java.util.function.Consumer;

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

    RunEvidenceWriter.NativeResult run(
            Path campaign,
            ResolvedRunContext.AutonomousDelivery context,
            Map<String, Path> executablePaths,
            DeliveryHostProfile hostProfile,
            long approvedMaxCostMinorUnits,
            Consumer<String> progressOutput)
            throws Exception {
        Objects.requireNonNull(context, "context must not be null");
        AutonomousDeliverySuiteManifest suite = context.suite();
        AutonomousDeliveryProgressReporter progress = new AutonomousDeliveryProgressReporter(progressOutput);
        AutonomousDeliveryPhasePolicy phasePolicy = AutonomousDeliveryPhasePolicy.resolve(suite);
        int phaseNumber = phasePolicy.phaseNumber();
        progress.phaseStarted(suite.phase());
        DeliveryToolchainSet toolchains = DeliveryToolchainSet.validate(executablePaths);
        validateHostProfile(hostProfile, context.platform());
        AutonomousDeliveryLiveBudget.Authorization liveBudget =
                AutonomousDeliveryLiveBudget.authorize(suite, approvedMaxCostMinorUnits);
        SecretPreflight.ResolvedSecrets requiredEnvironment =
                SecretPreflight.require(System.getenv(), context.agentProfile().requiredEnvironmentNames());
        List<String> selectedSecrets = context.agentProfile().credentialEnvironmentNames().stream()
                .map(requiredEnvironment::value)
                .toList();

        Instant startedAt = now();
        String buildCommit = context.productRevision().commit();
        Path gate = campaign.resolve("phase-" + phaseNumber)
                .resolve("build-" + buildCommit)
                .resolve("gate-" + GATE_TIME.format(startedAt));
        Files.createDirectories(gate.getParent());
        Files.createDirectory(gate);
        writeJson(gate.resolve("execution-plan.json"), context.nativePlan().artifact());
        writeJson(gate.resolve("live-budget-authorization.json"), liveBudget.artifact());
        long batchStartedNanos = System.nanoTime();
        Map<String, Object> deterministicAnalyze = phasePolicy.requiresDeterministicAnalyze()
                ? deterministicProbeExecutor.execute(
                        gate,
                        context.request().projectRoot(),
                        hostProfile,
                        AutonomousDeliveryDeterministicProbeExecutor.ProbeDefinition.READ_ONLY_ANALYZE)
                : Map.of("required", false);
        Map<String, Object> deterministicReplay = phasePolicy.requiresDeterministicReplay()
                ? deterministicProbeExecutor.execute(
                        gate,
                        context.request().projectRoot(),
                        hostProfile,
                        AutonomousDeliveryDeterministicProbeExecutor.ProbeDefinition.TRACE_REPLAY)
                : Map.of("required", false);

        boolean successful = phasePolicy.prerequisiteEvidencePassed(deterministicAnalyze, deterministicReplay);
        List<Map<String, Object>> results = new ArrayList<>();
        for (AutonomousDeliverySuiteManifest.CaseSelection selection : suite.cases()) {
            AutonomousDeliveryCase testCase = context.catalog().require(selection.caseId());
            for (int repetition = 1; repetition <= selection.repetitions(); repetition++) {
                Path repeat = gate.resolve("case-" + testCase.caseId()).resolve("repeat-%02d".formatted(repetition));
                Map<String, Object> result = repeatExecutor.execute(
                        repeat,
                        buildCommit,
                        suite,
                        testCase,
                        repetition,
                        context.agentProfile(),
                        toolchains,
                        hostProfile,
                        context.platform(),
                        context.productRevision(),
                        context.testConfigRevision(),
                        context.nativePlan().sha256(),
                        phasePolicy,
                        selectedSecrets,
                        progress);
                results.add(result);
                if (selection.blocking() && !Boolean.TRUE.equals(result.get("gatePassed"))) {
                    successful = false;
                }
            }
        }
        long batchElapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - batchStartedNanos);
        AutonomousDeliveryLiveBudget.Evidence liveBudgetEvidence =
                AutonomousDeliveryLiveBudget.evidence(suite, liveBudget, results, batchElapsedMillis);
        AutonomousDeliveryGateResultAggregator.NativeResult nativeResult =
                AutonomousDeliveryGateResultAggregator.aggregate(
                        suite,
                        context.platform(),
                        buildCommit,
                        successful,
                        deterministicAnalyze,
                        deterministicReplay,
                        results,
                        liveBudgetEvidence);
        progress.phaseCompleted(suite.phase(), nativeResult.successful());
        return new RunEvidenceWriter.NativeResult(
                gate,
                startedAt,
                now(),
                nativeResult.nativeStatus(),
                nativeResult.failureClassification(),
                nativeResult.successful(),
                liveBudgetEvidence.artifact(),
                List.of(),
                nativeResult.artifact(),
                selectedSecrets);
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

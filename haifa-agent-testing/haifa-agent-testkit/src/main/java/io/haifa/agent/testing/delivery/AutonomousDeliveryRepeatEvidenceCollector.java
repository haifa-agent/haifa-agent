package io.haifa.agent.testing.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.testing.evidence.EvidenceFinalizer;
import io.haifa.agent.testing.evidence.EvidenceSecretScanner;
import io.haifa.agent.testing.process.ProcessTreeCleanup;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Writes and finalizes one Repeat's versioned evidence after execution and policy evaluation. */
final class AutonomousDeliveryRepeatEvidenceCollector {
    private final ObjectMapper json;

    AutonomousDeliveryRepeatEvidenceCollector(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json must not be null");
    }

    Result collect(Path repeat, Input input, Collection<String> selectedSecrets) throws IOException {
        writeJson(repeat.resolve("acceptance-result.json"), input.acceptanceArtifact());
        writeJson(
                repeat.resolve("driver-contract-result.json"),
                input.driverContract().artifact());
        Map<String, Object> resultUsage = resultUsage(input.runtime(), input.wallTimeMillis());
        LinkedHashMap<String, Object> usageArtifact = new LinkedHashMap<>(resultUsage);
        usageArtifact.put("schemaVersion", 1);
        usageArtifact.put("iterations", input.iterations());
        usageArtifact.put("withinBudget", input.withinBudget());
        writeJson(repeat.resolve("usage.json"), usageArtifact);
        writeJson(
                repeat.resolve("failure-clusters.json"),
                Map.of(
                        "schemaVersion",
                        1,
                        "clusters",
                        input.runtime().failureClusters(),
                        "maximumAttempts",
                        input.runtime().maximumClusterAttempts()));
        writeJson(
                repeat.resolve("progress-evidence.json"),
                Map.of("schemaVersion", 1, "meaningfulProgress", input.runtime().progress()));
        writeJson(
                repeat.resolve("completion-evidence.json"),
                Map.of(
                        "schemaVersion",
                        1,
                        "acceptancePassed",
                        input.acceptancePassed(),
                        "caseTenBoundedConvergence",
                        input.caseTenBoundedConvergence(),
                        "terminalStateObserved",
                        input.runtime().terminalStateObserved()));
        writeJson(repeat.resolve("process-cleanup.json"), input.processCleanup().artifact(input.driverExitStatus()));
        EvidenceSecretScanner.Result secretScan = EvidenceSecretScanner.scan(repeat, selectedSecrets);
        writeJson(repeat.resolve("secret-scan.json"), secretScan);

        Result result = assemble(input, secretScan, resultUsage);
        writeJson(repeat.resolve("result.json"), result.resultArtifact());
        Files.deleteIfExists(repeat.resolve("driver-result.json"));
        EvidenceFinalizer.finalizeEvidence(repeat);
        return result;
    }

    static Result assemble(Input input, EvidenceSecretScanner.Result secretScan, Map<String, Object> resultUsage) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(secretScan, "secretScan must not be null");
        Objects.requireNonNull(resultUsage, "resultUsage must not be null");
        boolean gatePassed =
                input.preliminaryGatePassed() && input.processCleanup().passed() && secretScan.passed();
        CaseMetadata testCase = input.testCase();
        Map<String, Object> resultArtifact = Map.of(
                "schemaVersion",
                1,
                "caseId",
                testCase.caseId(),
                "caseVersion",
                testCase.caseVersion(),
                "repeat",
                input.repetition(),
                "termination",
                input.runtime().termination(),
                "successful",
                gatePassed,
                "hiddenAcceptance",
                input.acceptancePassed() ? "PASS" : "FAIL",
                "usage",
                resultUsage,
                "evidence",
                Map.of(
                        "workspaceChanged",
                        input.workspaceChanged(),
                        "validationAttempted",
                        input.runtime().validationAttempted(),
                        "diffInspected",
                        input.runtime().diffInspected(),
                        "failureAtomicity",
                        input.failureAtomicity()));

        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("caseId", testCase.caseId());
        summary.put("caseVersion", testCase.caseVersion());
        summary.put("repetition", input.repetition());
        summary.put("driverExitStatus", input.driverExitStatus());
        summary.put("driverContractPassed", input.driverContract().passed());
        summary.put("wallTimeSeconds", Math.round(input.wallTimeSeconds() * 1000.0) / 1000.0);
        summary.put("acceptancePassed", input.acceptancePassed());
        summary.put("boundedConvergence", input.boundedConvergence());
        summary.put("executionCalls", input.runtime().executionCalls());
        summary.put("scratchProvisionedCount", input.runtime().scratchProvisionedCount());
        summary.put("scratchCleanupFailures", input.runtime().scratchCleanupFailures());
        summary.put("scratchSatisfied", input.runtime().scratchSatisfied());
        summary.put("maximumFailureClusterAttempts", input.runtime().maximumClusterAttempts());
        summary.put("modelCalls", input.runtime().modelCalls());
        summary.put("toolCalls", input.runtime().toolCalls());
        summary.put("toolFailures", input.runtime().toolFailures());
        summary.put("inputTokens", input.runtime().inputTokens());
        summary.put("outputTokens", input.runtime().outputTokens());
        summary.put("workspaceChanged", input.workspaceChanged());
        summary.put("verificationPassed", input.verificationPassed());
        summary.put("failureAtomicity", input.failureAtomicity());
        summary.put("language", testCase.language());
        summary.put("taskType", testCase.taskType());
        summary.put("capabilities", testCase.capabilities());
        summary.put("riskDimensions", testCase.riskDimensions());
        summary.put("gatePassed", gatePassed);
        return new Result(Map.copyOf(summary), resultArtifact, gatePassed);
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

    private void writeJson(Path path, Object value) throws IOException {
        json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }

    record CaseMetadata(
            String caseId,
            String caseVersion,
            String language,
            String taskType,
            List<String> capabilities,
            List<String> riskDimensions) {
        CaseMetadata {
            Objects.requireNonNull(caseId, "caseId must not be null");
            Objects.requireNonNull(caseVersion, "caseVersion must not be null");
            Objects.requireNonNull(language, "language must not be null");
            Objects.requireNonNull(taskType, "taskType must not be null");
            capabilities = List.copyOf(Objects.requireNonNull(capabilities, "capabilities must not be null"));
            riskDimensions = List.copyOf(Objects.requireNonNull(riskDimensions, "riskDimensions must not be null"));
        }

        static CaseMetadata from(AutonomousDeliveryCase testCase) {
            return new CaseMetadata(
                    testCase.caseId(),
                    testCase.caseVersion(),
                    testCase.language(),
                    testCase.taskType(),
                    testCase.capabilities(),
                    testCase.riskDimensions());
        }
    }

    record Input(
            CaseMetadata testCase,
            int repetition,
            int driverExitStatus,
            TerminalDriverResultContract.Validation driverContract,
            double wallTimeSeconds,
            long wallTimeMillis,
            boolean acceptancePassed,
            Map<String, Object> acceptanceArtifact,
            boolean boundedConvergence,
            boolean caseTenBoundedConvergence,
            boolean preliminaryGatePassed,
            AutonomousDeliveryRuntimeEvidenceReader.Evidence runtime,
            ProcessTreeCleanup.Result processCleanup,
            int iterations,
            boolean withinBudget,
            boolean workspaceChanged,
            boolean verificationPassed,
            String failureAtomicity) {
        Input {
            Objects.requireNonNull(testCase, "testCase must not be null");
            Objects.requireNonNull(driverContract, "driverContract must not be null");
            acceptanceArtifact =
                    Map.copyOf(Objects.requireNonNull(acceptanceArtifact, "acceptanceArtifact must not be null"));
            Objects.requireNonNull(runtime, "runtime must not be null");
            Objects.requireNonNull(processCleanup, "processCleanup must not be null");
            Objects.requireNonNull(failureAtomicity, "failureAtomicity must not be null");
        }
    }

    record Result(Map<String, Object> summary, Map<String, Object> resultArtifact, boolean gatePassed) {
        Result {
            summary = Map.copyOf(Objects.requireNonNull(summary, "summary must not be null"));
            resultArtifact = Map.copyOf(Objects.requireNonNull(resultArtifact, "resultArtifact must not be null"));
        }
    }
}

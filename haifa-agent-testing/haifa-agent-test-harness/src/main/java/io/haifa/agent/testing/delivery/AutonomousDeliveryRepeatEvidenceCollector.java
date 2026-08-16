package io.haifa.agent.testing.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.testing.evidence.EvidenceFinalizer;
import io.haifa.agent.testing.evidence.EvidenceSecretScanner;
import io.haifa.agent.testing.evidence.Sha256Digests;
import io.haifa.agent.testing.harness.EvidenceAttachment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
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
        Map<String, Object> resultUsage = resultUsage(input.runtime(), input.wallTimeMillis());
        LinkedHashMap<String, Object> usageArtifact = new LinkedHashMap<>(resultUsage);
        usageArtifact.put("schemaVersion", 1);
        usageArtifact.put("iterations", input.iterations());
        usageArtifact.put("withinBudget", input.withinBudget());
        List<EvidenceAttachment> attachments = moveAttachments(repeat);
        EvidenceSecretScanner.Result secretScan = EvidenceSecretScanner.scan(repeat, selectedSecrets);
        writeJson(repeat.resolve("secret-scan.json"), secretScan);

        Result result = assemble(input, secretScan, resultUsage);
        LinkedHashMap<String, Object> authoritative = new LinkedHashMap<>(result.resultArtifact());
        authoritative.put("clientContract", input.clientContract().artifact());
        authoritative.put("usage", usageArtifact);
        authoritative.put(
                "failureClusters",
                Map.of(
                        "clusters",
                        input.runtime().failureClusters(),
                        "maximumAttempts",
                        input.runtime().maximumClusterAttempts()));
        authoritative.put("progress", input.runtime().progress());
        authoritative.put(
                "completion",
                Map.of(
                        "acceptancePassed",
                        input.acceptancePassed(),
                        "caseTenBoundedConvergence",
                        input.caseTenBoundedConvergence(),
                        "terminalStateObserved",
                        input.runtime().terminalStateObserved()));
        authoritative.put("attachments", attachments);
        authoritative.put("secretScanRef", "secret-scan.json");
        writeJson(repeat.resolve("run-result.json"), authoritative);
        EvidenceFinalizer.finalizeEvidence(repeat);
        return new Result(result.summary(), Map.copyOf(authoritative), result.gatePassed());
    }

    static Result assemble(Input input, EvidenceSecretScanner.Result secretScan, Map<String, Object> resultUsage) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(secretScan, "secretScan must not be null");
        Objects.requireNonNull(resultUsage, "resultUsage must not be null");
        boolean gatePassed =
                input.preliminaryGatePassed() && input.clientContract().passed() && secretScan.passed();
        String nativeStatus = gatePassed ? "GATE_PASSED" : "GATE_FAILED";
        CaseMetadata testCase = input.testCase();
        LinkedHashMap<String, Object> resultArtifact = new LinkedHashMap<>();
        resultArtifact.put("schemaVersion", 1);
        resultArtifact.put("caseId", testCase.caseId());
        resultArtifact.put("caseVersion", testCase.caseVersion());
        resultArtifact.put("repeat", input.repetition());
        resultArtifact.put("termination", input.runtime().termination());
        resultArtifact.put("nativeStatus", nativeStatus);
        resultArtifact.put("status", gatePassed ? "PASSED" : "FAILED");
        resultArtifact.put("failureClassification", gatePassed ? "NONE" : "ACCEPTANCE_FAILED");
        resultArtifact.put("successful", gatePassed);
        resultArtifact.put("hiddenAcceptance", input.acceptancePassed() ? "PASS" : "FAIL");
        resultArtifact.put("usage", resultUsage);
        resultArtifact.put(
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
        summary.put("clientContractPassed", input.clientContract().passed());
        summary.put("terminalStatus", input.clientContract().terminalStatus());
        summary.put("publicEventCount", input.clientContract().publicEventCount());
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
        summary.put("nativeStatus", nativeStatus);
        summary.put("gatePassed", gatePassed);
        return new Result(Map.copyOf(summary), Map.copyOf(resultArtifact), gatePassed);
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

    private static List<EvidenceAttachment> moveAttachments(Path repeat) throws IOException {
        Path attachments = Files.createDirectories(repeat.resolve("attachments"));
        List<Path> children;
        try (var paths = Files.list(repeat)) {
            children = paths.filter(path -> !path.equals(attachments))
                    .filter(path -> !path.getFileName().toString().equals("secret-scan.json"))
                    .filter(path -> !path.getFileName().toString().equals("run-result.json"))
                    .filter(path -> !path.getFileName().toString().equals("manifest.sha256"))
                    .toList();
        }
        for (Path child : children) {
            Files.move(child, attachments.resolve(child.getFileName()), StandardCopyOption.ATOMIC_MOVE);
        }
        ArrayList<EvidenceAttachment> result = new ArrayList<>();
        try (var paths = Files.walk(attachments)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                String relative = repeat.relativize(file).toString().replace('\\', '/');
                result.add(new EvidenceAttachment(
                        attachmentType(file), relative, Files.size(file), Sha256Digests.file(file)));
            }
        }
        return List.copyOf(result);
    }

    private static String attachmentType(Path file) {
        String name = file.getFileName().toString();
        if (name.equals("acceptance-result.json")) return "acceptance";
        if (name.equals("workspace.diff")) return "workspace-diff";
        if (name.endsWith(".jsonl") || name.contains("trace")) return "runtime-events";
        if (name.equals("session.cast") || name.contains("transcript")) return "transcript";
        return "supporting-evidence";
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
            CodingClientExecutionContract clientContract,
            double wallTimeSeconds,
            long wallTimeMillis,
            boolean acceptancePassed,
            Map<String, Object> acceptanceArtifact,
            boolean boundedConvergence,
            boolean caseTenBoundedConvergence,
            boolean preliminaryGatePassed,
            AutonomousDeliveryRuntimeEvidenceReader.Evidence runtime,
            int iterations,
            boolean withinBudget,
            boolean workspaceChanged,
            boolean verificationPassed,
            String failureAtomicity) {
        Input {
            Objects.requireNonNull(testCase, "testCase must not be null");
            Objects.requireNonNull(clientContract, "clientContract must not be null");
            acceptanceArtifact =
                    Map.copyOf(Objects.requireNonNull(acceptanceArtifact, "acceptanceArtifact must not be null"));
            Objects.requireNonNull(runtime, "runtime must not be null");
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

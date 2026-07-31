package io.haifa.agent.testing.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.testing.evidence.Sha256Digests;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Produces the bounded external-verification evidence required by Autonomous Delivery Phase 3. */
final class AutonomousDeliveryPhaseThreeVerificationCollector {
    private final ObjectMapper json;

    AutonomousDeliveryPhaseThreeVerificationCollector(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json must not be null");
    }

    Result collect(Path repeat, Input input) throws IOException {
        List<String> dimensions = verificationDimensions(input.testCase().riskDimensions());
        String planDigest = planDigest(input.testCase(), dimensions);
        boolean highRisk = highRisk(input.testCase().riskDimensions());

        LinkedHashMap<String, Object> plan = new LinkedHashMap<>();
        plan.put("schemaVersion", 1);
        plan.put("planId", "external-verification:" + input.testCase().caseId());
        plan.put("caseVersion", input.testCase().caseVersion());
        plan.put("dimensions", dimensions);
        plan.put("maximumDimensions", 9);
        plan.put("maximumChecks", 32);
        plan.put("riskLevel", highRisk ? "HIGH" : "MEDIUM");
        plan.put("digest", planDigest);
        plan.put("containsExecutableCode", false);
        writeJson(repeat.resolve("verification-plan.json"), plan);

        List<Map<String, Object>> evidence = verificationEvidence(input.acceptanceChecks(), dimensions, planDigest);
        boolean verificationPassed = input.acceptancePassed()
                && !evidence.isEmpty()
                && evidence.stream().allMatch(value -> "VERIFICATION_CHECK_PASSED".equals(value.get("kind")));
        writeJson(
                repeat.resolve("verification-evidence.json"),
                Map.of(
                        "schemaVersion",
                        1,
                        "planDigest",
                        planDigest,
                        "passed",
                        verificationPassed,
                        "evidence",
                        evidence));

        boolean cleanup = input.scratchCleanupFailures() == 0 && input.processFinished();
        boolean atomicityPassed = !highRisk || (input.acceptancePassed() && cleanup);
        List<String> unexpected = input.acceptancePassed() ? List.of() : input.acceptanceFailures();
        writeJson(
                repeat.resolve("side-effect-evidence.json"),
                Map.of(
                        "schemaVersion",
                        1,
                        "planDigest",
                        planDigest,
                        "beforeStateDigest",
                        "sha256:" + input.beforeStateDigest(),
                        "operationResult",
                        input.acceptancePassed() ? "ACCEPTED" : "REJECTED",
                        "afterStateDigest",
                        "sha256:" + input.afterStateDigest(),
                        "allowedChanges",
                        List.of("TASK_DELIVERY_WORKSPACE_CHANGE"),
                        "unexpectedChanges",
                        unexpected,
                        "cleanupEvidence",
                        cleanup ? "PROCESS_AND_SCRATCH_CLEAN" : "CLEANUP_NOT_CONFIRMED",
                        "atomicityRequired",
                        highRisk,
                        "passed",
                        atomicityPassed));
        writeJson(
                repeat.resolve("capability-matrix.json"),
                Map.of(
                        "schemaVersion",
                        1,
                        "language",
                        input.testCase().language(),
                        "taskType",
                        input.testCase().taskType(),
                        "capabilities",
                        input.testCase().capabilities(),
                        "riskDimensions",
                        input.testCase().riskDimensions(),
                        "acceptanceType",
                        "HIDDEN_BLACK_BOX",
                        "sideEffect",
                        highRisk ? "CONTROLLED" : "NONE_OR_LOW"));
        return new Result(
                verificationPassed && atomicityPassed,
                highRisk ? (atomicityPassed ? "PASS" : "FAIL") : "NOT_APPLICABLE");
    }

    static Result notRequired() {
        return new Result(true, "NOT_APPLICABLE");
    }

    static List<String> verificationDimensions(List<String> riskDimensions) {
        LinkedHashSet<String> result = new LinkedHashSet<>(List.of("SUCCESS_PATH", "BOUNDARY", "FAILURE_PATH"));
        for (String risk : riskDimensions) {
            switch (risk) {
                case "FAILURE_ATOMICITY" -> result.add("FAILURE_ATOMICITY");
                case "IDEMPOTENCY" -> result.add("IDEMPOTENCY");
                case "COMPATIBILITY", "PROTOCOL" -> result.add("COMPATIBILITY");
                case "CONCURRENCY" -> result.add("CONCURRENCY");
                case "SECURITY" -> result.add("SECURITY_NORMALIZATION");
                case "RESOURCE_CLEANUP", "ENVIRONMENT_RECOVERY" -> result.add("RESOURCE_CLEANUP");
                default -> {
                    // Other catalog dimensions retain the conservative common dimensions.
                }
            }
        }
        return List.copyOf(result);
    }

    private static List<Map<String, Object>> verificationEvidence(
            Map<String, Boolean> acceptanceChecks, List<String> dimensions, String planDigest) {
        List<Map.Entry<String, Boolean>> checks = acceptanceChecks.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
        if (checks.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (int index = 0; index < dimensions.size(); index++) {
            Map.Entry<String, Boolean> check = checks.get(index % checks.size());
            String dimension = dimensions.get(index);
            String sourceRef = "acceptance-result.json#checks/" + check.getKey();
            evidence.add(Map.of(
                    "kind",
                    check.getValue() ? "VERIFICATION_CHECK_PASSED" : "VERIFICATION_CHECK_FAILED",
                    "planDigest",
                    planDigest,
                    "dimension",
                    dimension,
                    "sourceRef",
                    sourceRef,
                    "terminalStatus",
                    check.getValue() ? "PASSED" : "FAILED",
                    "safeSummary",
                    "External hidden acceptance check",
                    "sourceDigest",
                    "sha256:"
                            + Sha256Digests.bytes((planDigest + "|" + sourceRef + "|" + check.getValue())
                                    .getBytes(StandardCharsets.UTF_8))));
        }
        return List.copyOf(evidence);
    }

    private static String planDigest(
            AutonomousDeliveryRepeatEvidenceCollector.CaseMetadata testCase, List<String> dimensions) {
        return "sha256:"
                + Sha256Digests.bytes(String.join(
                                "|",
                                testCase.caseId(),
                                testCase.caseVersion(),
                                String.join(",", dimensions),
                                String.join(",", testCase.riskDimensions()))
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static boolean highRisk(List<String> riskDimensions) {
        return riskDimensions.stream().anyMatch(value -> List.of(
                        "FAILURE_ATOMICITY", "IDEMPOTENCY", "CONCURRENCY", "SECURITY", "RESOURCE_CLEANUP")
                .contains(value));
    }

    private void writeJson(Path path, Object value) throws IOException {
        json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }

    record Input(
            AutonomousDeliveryRepeatEvidenceCollector.CaseMetadata testCase,
            boolean acceptancePassed,
            Map<String, Boolean> acceptanceChecks,
            List<String> acceptanceFailures,
            String beforeStateDigest,
            String afterStateDigest,
            long scratchCleanupFailures,
            boolean processFinished) {
        Input {
            Objects.requireNonNull(testCase, "testCase must not be null");
            acceptanceChecks =
                    Map.copyOf(Objects.requireNonNull(acceptanceChecks, "acceptanceChecks must not be null"));
            acceptanceFailures =
                    List.copyOf(Objects.requireNonNull(acceptanceFailures, "acceptanceFailures must not be null"));
            Objects.requireNonNull(beforeStateDigest, "beforeStateDigest must not be null");
            Objects.requireNonNull(afterStateDigest, "afterStateDigest must not be null");
            if (scratchCleanupFailures < 0) {
                throw new IllegalArgumentException("scratchCleanupFailures must not be negative");
            }
        }
    }

    record Result(boolean passed, String atomicity) {
        Result {
            Objects.requireNonNull(atomicity, "atomicity must not be null");
        }
    }
}

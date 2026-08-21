package io.haifa.agent.application.project.product.coding.delivery;

import io.haifa.agent.core.step.AgentStep;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Reconstructs the minimal delivery facts used by Coding completion and context. */
public final class CodingDeliveryEvidenceLedger {
    private static final Set<String> NO_CHANGE_CODES =
            Set.of("ALREADY_SATISFIED", "ARCHITECTURE_STOP", "SECURITY_STOP", "DETERMINISTIC_BLOCKER");
    private static final Set<String> MUTATION_TOOLS = Set.of(
            "file.create",
            "file.write",
            "file.delete",
            "file.move",
            "file.patch",
            "file_create",
            "file_write",
            "file_delete",
            "file_move",
            "file_patch");
    // Legacy identities remain readable for persisted frozen Runs; new catalogs do not disclose git.* Tools.
    private static final Set<String> READ_TOOLS = Set.of(
            "file.list",
            "file.stat",
            "file.read",
            "file.search",
            "file.diff",
            "git.inspect",
            "git.status",
            "file_list",
            "file_stat",
            "file_read",
            "file_search",
            "file_diff",
            "git_inspect",
            "git_status",
            "skill.load",
            "skill.resource.read",
            "skill_load",
            "skill_resource_read");
    private static final Set<String> DIFF_TOOLS = Set.of("file.diff", "git.diff", "file_diff", "git_diff");
    private static final Set<String> EXECUTION_TOOLS = Set.of("execution.run", "execution_run");

    private final RuntimeStateRepository state;

    public CodingDeliveryEvidenceLedger(RuntimeStateRepository state) {
        this.state = Objects.requireNonNull(state, "state must not be null");
    }

    public Snapshot reconstruct(io.haifa.agent.core.run.AgentRunId runId) {
        Map<String, AgentStep> steps = new LinkedHashMap<>();
        state.steps(runId).forEach(step -> steps.put(step.id().value(), step));
        EnumSet<CodingDeliveryEvidenceKind> facts = EnumSet.noneOf(CodingDeliveryEvidenceKind.class);
        Map<CodingDeliveryEvidenceKind, Integer> latestDeliveryEvidence =
                new java.util.EnumMap<>(CodingDeliveryEvidenceKind.class);
        List<CodingValidationAttemptEvidence> validationAttempts = new ArrayList<>();
        List<ToolCall> calls = state.toolCalls(runId).stream()
                .sorted(Comparator.comparing(ToolCall::requestedAt)
                        .thenComparing(call -> call.id().value()))
                .toList();
        for (int index = 0; index < calls.size(); index++) {
            ToolCall call = calls.get(index);
            EnumSet<CodingDeliveryEvidenceKind> callFacts = EnumSet.noneOf(CodingDeliveryEvidenceKind.class);
            collect(call, steps.get(call.stepId().value()), callFacts, validationAttempts);
            facts.addAll(callFacts);
            int position = index;
            callFacts.forEach(kind -> latestDeliveryEvidence.put(kind, position));
        }
        return new Snapshot(facts, latestDeliveryEvidence, validationAttempts);
    }

    private static void collect(
            ToolCall call,
            AgentStep step,
            EnumSet<CodingDeliveryEvidenceKind> facts,
            List<CodingValidationAttemptEvidence> validationAttempts) {
        Map<String, Object> data = call.result()
                .map(result -> result.structuredData())
                .orElseGet(() -> java.util.Optional.ofNullable(step)
                        .flatMap(AgentStep::result)
                        .map(result -> result.data())
                        .orElse(Map.of()));
        if (call.status() == ToolCallStatus.COMPLETED && READ_TOOLS.contains(call.toolName())) {
            facts.add(CodingDeliveryEvidenceKind.READ_ONLY_INSPECTION);
        }
        if (call.status() == ToolCallStatus.FAILED
                && READ_TOOLS.contains(call.toolName())
                && data.containsKey("errorCode")) {
            facts.add(CodingDeliveryEvidenceKind.READ_ONLY_INSPECTION);
            facts.add(CodingDeliveryEvidenceKind.BLOCKER_CONFIRMED);
        }
        if (MUTATION_TOOLS.contains(call.toolName()) && hasChangeSetReference(data)) {
            facts.add(CodingDeliveryEvidenceKind.WORKSPACE_CHANGE);
        }
        CodingChangeReviewArtifact.fromStructuredData(data.get("changeReviewArtifact"))
                .filter(CodingChangeReviewArtifact::complete)
                .filter(review -> reviewReferencesResultChangeSets(review, data))
                .ifPresent(review -> facts.add(CodingDeliveryEvidenceKind.DETERMINISTIC_CHANGE_REVIEW));
        if (call.status() == ToolCallStatus.COMPLETED && DIFF_TOOLS.contains(call.toolName())) {
            facts.add(CodingDeliveryEvidenceKind.DIFF_INSPECTION);
        }
        if (!EXECUTION_TOOLS.contains(call.toolName()) || data.isEmpty()) return;

        Object deliveryEvidenceCode = data.get("deliveryEvidenceCode");
        if (deliveryEvidenceCode instanceof String code) {
            try {
                facts.add(CodingDeliveryEvidenceKind.valueOf(code));
            } catch (IllegalArgumentException ignored) {
                // Frozen or future executions may carry evidence unknown to this Runtime version.
            }
        }

        String declaredFamily = String.valueOf(
                data.getOrDefault("declaredOperationFamily", data.getOrDefault("operationFamily", "UNKNOWN")));
        String effectiveFamily = String.valueOf(data.getOrDefault(
                "effectiveOperationFamily",
                data.containsKey("commandOperation") ? data.get("commandOperation") : declaredFamily));
        String evidenceFamily = "UNKNOWN".equals(effectiveFamily) ? declaredFamily : effectiveFamily;
        String status = String.valueOf(data.getOrDefault("status", "UNKNOWN"));
        String semanticOutcome = String.valueOf(data.getOrDefault("semanticOutcome", "UNKNOWN"));
        boolean trustedReadOnly = trustedReadOnlyClassification(data);
        if (data.containsKey("fileChangeSetId")) {
            facts.add(CodingDeliveryEvidenceKind.WORKSPACE_CHANGE);
        }
        if (("INSPECT".equals(evidenceFamily) || "DIFF".equals(evidenceFamily))
                && trustedReadOnly
                && trustedOperationFamily(data, evidenceFamily)) {
            facts.add(CodingDeliveryEvidenceKind.READ_ONLY_INSPECTION);
        }
        if ("DIFF".equals(evidenceFamily)
                && ("SUCCEEDED".equals(status) || "EXPECTED_VARIANT".equals(semanticOutcome))
                && trustedReadOnly
                && trustedOperationFamily(data, evidenceFamily)) {
            facts.add(CodingDeliveryEvidenceKind.DIFF_INSPECTION);
        }
        if ("BUILD".equals(declaredFamily) || "TEST".equals(declaredFamily)) {
            CodingValidationAttemptEvidence validation = CodingValidationAttemptEvidence.fromStructuredData(
                            data.get("validationEvidence"))
                    .orElseGet(() -> legacyValidation(status));
            validationAttempts.add(validation);
            facts.add(CodingDeliveryEvidenceKind.VALIDATION_ATTEMPT);
            facts.add(
                    validation.status() == CodingValidationStatus.PASSED
                            ? CodingDeliveryEvidenceKind.VALIDATION_PASSED
                            : CodingDeliveryEvidenceKind.VALIDATION_FAILED);
        }
        if (data.containsKey("failureCategory") && !"SUCCEEDED".equals(status)) {
            facts.add(CodingDeliveryEvidenceKind.BLOCKER_CONFIRMED);
        }
        Object noChangeCode = data.get("noChangeJustificationCode");
        if (noChangeCode instanceof String code
                && NO_CHANGE_CODES.contains(code)
                && (("SUCCEEDED".equals(status) && ("BUILD".equals(declaredFamily) || "TEST".equals(declaredFamily)))
                        || (data.containsKey("failureCategory") && !"SUCCEEDED".equals(status)))) {
            facts.add(CodingDeliveryEvidenceKind.NO_CHANGE_JUSTIFICATION);
        }
    }

    private static CodingValidationAttemptEvidence legacyValidation(String status) {
        return new CodingValidationAttemptEvidence(
                CodingValidationAttemptEvidence.SCHEMA_VERSION,
                "SUCCEEDED".equals(status) ? CodingValidationStatus.PASSED : CodingValidationStatus.FAILED,
                null,
                null,
                null,
                CodingValidationScope.UNKNOWN,
                "LEGACY_TOOL_RESULT",
                "TEST_COUNTS_UNAVAILABLE");
    }

    private static boolean reviewReferencesResultChangeSets(
            CodingChangeReviewArtifact review, Map<String, Object> data) {
        Set<String> resultRefs = new java.util.LinkedHashSet<>();
        addString(data.get("changeSetId"), resultRefs);
        addString(data.get("fileChangeSetId"), resultRefs);
        if (data.get("changeSetIds") instanceof List<?> values) values.forEach(value -> addString(value, resultRefs));
        return !resultRefs.isEmpty() && resultRefs.equals(new java.util.LinkedHashSet<>(review.changeSetIds()));
    }

    private static void addString(Object value, Set<String> target) {
        if (value instanceof String text && !text.isBlank()) target.add(text);
    }

    private static boolean hasChangeSetReference(Map<String, Object> data) {
        if (data.get("changeSetId") instanceof String value && !value.isBlank()) return true;
        if (!(data.get("changeSetIds") instanceof List<?> values)) return false;
        return values.stream().anyMatch(value -> value instanceof String text && !text.isBlank());
    }

    private static boolean trustedReadOnlyClassification(Map<String, Object> data) {
        if (!data.containsKey("commandTarget") && !data.containsKey("commandRisk")) {
            return true; // Frozen legacy execution results predate trusted command classification.
        }
        String target = String.valueOf(data.getOrDefault("commandTarget", "OTHER"));
        String risk = String.valueOf(data.getOrDefault("commandRisk", "UNKNOWN"));
        return ("OTHER".equals(target) && ("NOT_APPLICABLE".equals(risk) || "UNKNOWN".equals(risk)))
                || "LOCAL_READ".equals(risk)
                || "NETWORK_READ".equals(risk);
    }

    private static boolean trustedOperationFamily(Map<String, Object> data, String family) {
        if (!data.containsKey("commandOperation")) {
            return true; // Frozen legacy results predate trusted operation classification.
        }
        if ("OTHER".equals(String.valueOf(data.getOrDefault("commandTarget", "OTHER")))
                && ("NOT_APPLICABLE".equals(String.valueOf(data.getOrDefault("commandRisk", "UNKNOWN")))
                        || "UNKNOWN".equals(String.valueOf(data.getOrDefault("commandRisk", "UNKNOWN"))))) {
            return true; // Generic commands retain the declared delivery intent, never authorization or risk.
        }
        String operation = String.valueOf(data.getOrDefault("commandOperation", "UNKNOWN"));
        return switch (family) {
            case "DIFF" -> "DIFF".equals(operation);
            case "INSPECT" -> "INSPECT".equals(operation) || "DIFF".equals(operation);
            default -> false;
        };
    }

    public record Snapshot(
            Set<CodingDeliveryEvidenceKind> kinds,
            Map<CodingDeliveryEvidenceKind, Integer> latestDeliveryEvidence,
            List<CodingValidationAttemptEvidence> validationAttempts) {
        public Snapshot {
            kinds = Set.copyOf(Objects.requireNonNull(kinds, "kinds must not be null"));
            latestDeliveryEvidence = Map.copyOf(
                    Objects.requireNonNull(latestDeliveryEvidence, "latestDeliveryEvidence must not be null"));
            validationAttempts =
                    List.copyOf(Objects.requireNonNull(validationAttempts, "validationAttempts must not be null"));
        }

        public Snapshot(Set<CodingDeliveryEvidenceKind> kinds) {
            this(kinds, Map.of(), List.of());
        }

        public Snapshot(
                Set<CodingDeliveryEvidenceKind> kinds,
                Map<CodingDeliveryEvidenceKind, Integer> latestDeliveryEvidence) {
            this(kinds, latestDeliveryEvidence, List.of());
        }

        public boolean has(CodingDeliveryEvidenceKind kind) {
            return kinds.contains(kind);
        }

        public boolean hasAfter(CodingDeliveryEvidenceKind kind, CodingDeliveryEvidenceKind predecessor) {
            Integer position = latestDeliveryEvidence.get(kind);
            Integer previous = latestDeliveryEvidence.get(predecessor);
            return position != null && previous != null && position > previous;
        }

        public boolean hasAtOrAfter(CodingDeliveryEvidenceKind kind, CodingDeliveryEvidenceKind predecessor) {
            Integer position = latestDeliveryEvidence.get(kind);
            Integer previous = latestDeliveryEvidence.get(predecessor);
            return position != null && previous != null && position >= previous;
        }

        public List<String> codes() {
            return kinds.stream()
                    .sorted(Comparator.comparing(Enum::name))
                    .map(Enum::name)
                    .toList();
        }

        public boolean latestValidationPassed() {
            return !validationAttempts.isEmpty()
                    && validationAttempts.getLast().status() == CodingValidationStatus.PASSED;
        }

        public boolean latestValidationFailed() {
            return !validationAttempts.isEmpty()
                    && validationAttempts.getLast().status() == CodingValidationStatus.FAILED;
        }
    }
}

package io.haifa.agent.application.project.product.coding.delivery;

import io.haifa.agent.core.step.AgentStep;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
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
        state.toolCalls(runId).stream()
                .sorted(Comparator.comparing(ToolCall::requestedAt)
                        .thenComparing(call -> call.id().value()))
                .forEach(call -> collect(call, steps.get(call.stepId().value()), facts));
        return new Snapshot(facts);
    }

    private static void collect(ToolCall call, AgentStep step, EnumSet<CodingDeliveryEvidenceKind> facts) {
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
        if (call.status() == ToolCallStatus.COMPLETED
                && MUTATION_TOOLS.contains(call.toolName())
                && data.containsKey("changeSetId")) {
            facts.add(CodingDeliveryEvidenceKind.WORKSPACE_CHANGE);
        }
        if (call.status() == ToolCallStatus.COMPLETED && DIFF_TOOLS.contains(call.toolName())) {
            facts.add(CodingDeliveryEvidenceKind.DIFF_INSPECTION);
        }
        if (!EXECUTION_TOOLS.contains(call.toolName()) || data.isEmpty()) return;

        String family = String.valueOf(data.getOrDefault("operationFamily", "UNKNOWN"));
        String status = String.valueOf(data.getOrDefault("status", "UNKNOWN"));
        if (data.containsKey("fileChangeSetId")) {
            facts.add(CodingDeliveryEvidenceKind.WORKSPACE_CHANGE);
        }
        if ("INSPECT".equals(family) || "DIFF".equals(family)) {
            facts.add(CodingDeliveryEvidenceKind.READ_ONLY_INSPECTION);
        }
        if ("DIFF".equals(family) && "SUCCEEDED".equals(status)) {
            facts.add(CodingDeliveryEvidenceKind.DIFF_INSPECTION);
        }
        if ("BUILD".equals(family) || "TEST".equals(family)) {
            facts.add(CodingDeliveryEvidenceKind.VALIDATION_ATTEMPT);
            facts.add(
                    "SUCCEEDED".equals(status)
                            ? CodingDeliveryEvidenceKind.VALIDATION_PASSED
                            : CodingDeliveryEvidenceKind.VALIDATION_FAILED);
        }
        if (data.containsKey("failureCategory") && !"SUCCEEDED".equals(status)) {
            facts.add(CodingDeliveryEvidenceKind.BLOCKER_CONFIRMED);
        }
        Object noChangeCode = data.get("noChangeJustificationCode");
        if (noChangeCode instanceof String code
                && NO_CHANGE_CODES.contains(code)
                && (("SUCCEEDED".equals(status) && ("BUILD".equals(family) || "TEST".equals(family)))
                        || (data.containsKey("failureCategory") && !"SUCCEEDED".equals(status)))) {
            facts.add(CodingDeliveryEvidenceKind.NO_CHANGE_JUSTIFICATION);
        }
    }

    public record Snapshot(Set<CodingDeliveryEvidenceKind> kinds) {
        public Snapshot {
            kinds = Set.copyOf(Objects.requireNonNull(kinds, "kinds must not be null"));
        }

        public boolean has(CodingDeliveryEvidenceKind kind) {
            return kinds.contains(kind);
        }

        public List<String> codes() {
            return kinds.stream()
                    .sorted(Comparator.comparing(Enum::name))
                    .map(Enum::name)
                    .toList();
        }
    }
}

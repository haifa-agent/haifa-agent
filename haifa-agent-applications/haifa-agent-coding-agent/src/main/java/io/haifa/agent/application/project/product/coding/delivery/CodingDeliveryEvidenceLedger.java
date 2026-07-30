package io.haifa.agent.application.project.product.coding.delivery;

import io.haifa.agent.core.step.AgentStep;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministic projection of authoritative ToolCall and AgentStep outcomes; model text is ignored. */
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
        for (AgentStep step : state.steps(runId)) steps.put(step.id().value(), step);
        List<CodingDeliveryEvidence> values = new ArrayList<>();
        for (ToolCall call : state.toolCalls(runId).stream()
                .sorted(Comparator.comparing(ToolCall::requestedAt)
                        .thenComparing(value -> value.id().value()))
                .toList()) {
            Map<String, Object> data = call.result()
                    .map(result -> result.structuredData())
                    .orElseGet(() -> java.util.Optional.ofNullable(
                                    steps.get(call.stepId().value()))
                            .flatMap(AgentStep::result)
                            .map(result -> result.data())
                            .orElse(Map.of()));
            if (call.status() == ToolCallStatus.COMPLETED && READ_TOOLS.contains(call.toolName())) {
                add(values, call, CodingDeliveryEvidenceKind.READ_ONLY_INSPECTION, "Read-only workspace evidence");
            }
            if (call.status() == ToolCallStatus.FAILED
                    && READ_TOOLS.contains(call.toolName())
                    && data.containsKey("errorCode")) {
                add(values, call, CodingDeliveryEvidenceKind.READ_ONLY_INSPECTION, "Read-only failure evidence");
                add(values, call, CodingDeliveryEvidenceKind.BLOCKER_CONFIRMED, "Read-only blocker recorded");
            }
            if (call.status() == ToolCallStatus.COMPLETED
                    && MUTATION_TOOLS.contains(call.toolName())
                    && data.containsKey("changeSetId")) {
                add(values, call, CodingDeliveryEvidenceKind.WORKSPACE_CHANGE, "Workspace change recorded");
                if (logicalDocumentationPath(call.arguments().values())) {
                    add(
                            values,
                            call,
                            CodingDeliveryEvidenceKind.DOCUMENTATION_CHANGED,
                            "Documentation change recorded");
                }
            }
            if (call.status() == ToolCallStatus.COMPLETED && DIFF_TOOLS.contains(call.toolName())) {
                add(values, call, CodingDeliveryEvidenceKind.DIFF_INSPECTION, "Diff inspected");
            }
            if (!EXECUTION_TOOLS.contains(call.toolName()) || data.isEmpty()) continue;
            String family = String.valueOf(data.getOrDefault("operationFamily", "UNKNOWN"));
            String status = String.valueOf(data.getOrDefault("status", "UNKNOWN"));
            if (data.containsKey("fileChangeSetId")) {
                add(values, call, CodingDeliveryEvidenceKind.WORKSPACE_CHANGE, "Execution change recorded");
            }
            if ("INSPECT".equals(family) || "DIFF".equals(family)) {
                add(values, call, CodingDeliveryEvidenceKind.READ_ONLY_INSPECTION, "Read-only execution recorded");
            }
            if ("DIFF".equals(family) && "SUCCEEDED".equals(status)) {
                add(values, call, CodingDeliveryEvidenceKind.DIFF_INSPECTION, "Diff inspected");
            }
            if ("BUILD".equals(family) || "TEST".equals(family)) {
                add(values, call, CodingDeliveryEvidenceKind.VALIDATION_ATTEMPT, "Validation attempted");
                add(
                        values,
                        call,
                        "SUCCEEDED".equals(status)
                                ? CodingDeliveryEvidenceKind.VALIDATION_PASSED
                                : CodingDeliveryEvidenceKind.VALIDATION_FAILED,
                        "SUCCEEDED".equals(status) ? "Validation passed" : "Validation did not pass");
            }
            if (data.containsKey("failureCategory") && !"SUCCEEDED".equals(status)) {
                add(
                        values,
                        call,
                        CodingDeliveryEvidenceKind.BLOCKER_CONFIRMED,
                        "Structured execution blocker recorded");
            }
            Object noChangeCode = data.get("noChangeJustificationCode");
            if (noChangeCode instanceof String code
                    && NO_CHANGE_CODES.contains(code)
                    && (("SUCCEEDED".equals(status) && ("BUILD".equals(family) || "TEST".equals(family)))
                            || (data.containsKey("failureCategory") && !"SUCCEEDED".equals(status)))) {
                add(
                        values,
                        call,
                        CodingDeliveryEvidenceKind.NO_CHANGE_JUSTIFICATION,
                        "Evidence-backed no-change justification recorded");
            }
            var step = steps.get(call.stepId().value());
            if (step != null
                    && step.result().stream()
                            .anyMatch(result -> !result.artifacts().isEmpty())) {
                add(values, call, CodingDeliveryEvidenceKind.ARTIFACT_PUBLISHED, "Artifact reference recorded");
            }
        }
        return new Snapshot(values);
    }

    private static boolean logicalDocumentationPath(Map<String, Object> arguments) {
        for (String key : List.of("path", "destination")) {
            Object value = arguments.get(key);
            if (value instanceof String path
                    && (path.startsWith("docs/") || path.endsWith(".md") || path.endsWith(".adoc"))) {
                return true;
            }
        }
        return false;
    }

    private static void add(
            List<CodingDeliveryEvidence> values, ToolCall call, CodingDeliveryEvidenceKind kind, String safeSummary) {
        String sourceRef = "tool-call:" + call.id().value();
        String digest = digest(kind.name() + "|" + sourceRef + "|" + call.version());
        values.add(new CodingDeliveryEvidence(
                kind.name().toLowerCase(java.util.Locale.ROOT) + ":" + call.id().value(),
                kind,
                sourceRef,
                digest,
                call.completedAt().orElse(call.requestedAt()),
                safeSummary));
    }

    private static String digest(String value) {
        try {
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record Snapshot(List<CodingDeliveryEvidence> evidence) {
        public Snapshot {
            evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence must not be null"));
        }

        public boolean has(CodingDeliveryEvidenceKind kind) {
            return evidence.stream().anyMatch(value -> value.kind() == kind);
        }

        public Set<CodingDeliveryEvidenceKind> kinds() {
            EnumSet<CodingDeliveryEvidenceKind> kinds = EnumSet.noneOf(CodingDeliveryEvidenceKind.class);
            evidence.forEach(value -> kinds.add(value.kind()));
            return Set.copyOf(kinds);
        }

        public List<String> codes() {
            return kinds().stream()
                    .sorted(Comparator.comparing(Enum::name))
                    .map(Enum::name)
                    .toList();
        }
    }
}

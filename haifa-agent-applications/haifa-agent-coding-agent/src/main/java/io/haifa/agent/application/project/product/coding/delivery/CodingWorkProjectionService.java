package io.haifa.agent.application.project.product.coding.delivery;

import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.policy.api.PolicyDigest;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministically rebuilds one bounded Coding work projection from the existing Runtime fact stores. */
public final class CodingWorkProjectionService {
    public static final String SCHEMA_VERSION = "coding-work-projection/1";
    private static final Set<String> READ_TOOLS = Set.of(
            "file.list",
            "file.stat",
            "file.read",
            "file.search",
            "file.diff",
            "file_list",
            "file_stat",
            "file_read",
            "file_search",
            "file_diff",
            "skill.load",
            "skill.resource.read",
            "skill_load",
            "skill_resource_read");
    private final RuntimeStateRepository state;
    private final CodingTaskModeResolver taskModes;
    private final CodingDeliveryEvidenceLedger evidence;
    private final CodingDeliveryProfile profile;
    private final TimeProvider time;

    public CodingWorkProjectionService(
            RuntimeStateRepository state,
            CodingTaskModeResolver taskModes,
            CodingDeliveryEvidenceLedger evidence,
            CodingDeliveryProfile profile,
            TimeProvider time) {
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.taskModes = Objects.requireNonNull(taskModes, "taskModes must not be null");
        this.evidence = Objects.requireNonNull(evidence, "evidence must not be null");
        this.profile = Objects.requireNonNull(profile, "profile must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
    }

    public CodingWorkProjection project(AgentRun run) {
        Objects.requireNonNull(run, "run must not be null");
        CodingTaskIntent taskIntent = taskModes.resolve(run);
        CodingDeliveryEvidenceLedger.Snapshot snapshot = evidence.reconstruct(run.id());
        var refs = new EnumMap<RefKind, LinkedHashSet<String>>(RefKind.class);
        for (RefKind kind : RefKind.values()) refs.put(kind, new LinkedHashSet<>());
        Map<String, Integer> failures = new LinkedHashMap<>();
        state.toolCalls(run.id()).stream()
                .sorted(Comparator.comparing(ToolCall::requestedAt)
                        .thenComparing(call -> call.id().value()))
                .forEach(call -> collect(call, refs, failures));
        state.plan(run.id()).ifPresent(plan -> plan.items().forEach(item -> {
            RefKind kind =
                    switch (item.status()) {
                        case COMPLETED, CANCELLED, SKIPPED -> RefKind.DONE_ITEM;
                        case BLOCKED -> RefKind.BLOCKED_ITEM;
                        case PENDING, IN_PROGRESS -> RefKind.IN_PROGRESS_ITEM;
                    };
            add(
                    refs.get(kind),
                    reference("todo", item.id().toString(), item.status().name()));
        }));
        failures.forEach((key, count) -> add(refs.get(RefKind.FAILURE), key + ":" + count));

        List<String> missing = missingEvidence(taskIntent, snapshot);
        CodingWorkPhase phase = phase(taskIntent, snapshot, refs.get(RefKind.BLOCKED_ITEM), missing);
        int remainingModelCalls =
                remaining(run.budget().maxModelCalls(), run.usage().modelCalls());
        int remainingToolCalls =
                remaining(run.budget().maxToolCalls(), run.usage().toolCalls());
        int modelPercent = percent(remainingModelCalls, run.budget().maxModelCalls());
        int toolPercent = percent(remainingToolCalls, run.budget().maxToolCalls());
        long wallUsed =
                Math.max(0, Duration.between(run.createdAt(), time.now()).toMillis());
        long wallRemaining = Math.max(0, run.limits().maxWallTimeMillis() - wallUsed);
        int wallPercent = percent(wallRemaining, run.limits().maxWallTimeMillis());
        int remainingPercent = Math.min(modelPercent, Math.min(toolPercent, wallPercent));
        boolean reserveActive = !missing.isEmpty()
                && (modelPercent <= profile.modelCallsReservePercent()
                        || toolPercent <= profile.toolCallsReservePercent()
                        || wallPercent <= profile.wallTimeReservePercent());
        String taskContractDigest = taskContractDigest(run, taskIntent);
        String deliveryIntent =
                switch (taskIntent) {
                    case ANALYZE, REVIEW -> "READ_ONLY";
                    case CHANGE, CREATE -> "WORKSPACE_CHANGE_ONLY";
                    case UNKNOWN -> "UNSPECIFIED";
                };
        List<String> failureSummaries = values(refs, RefKind.FAILURE);
        String digest = PolicyDigest.sha256Fields(List.of(
                SCHEMA_VERSION,
                taskContractDigest,
                taskIntent.name(),
                phase.name(),
                String.join(",", values(refs, RefKind.DONE_ITEM)),
                String.join(",", values(refs, RefKind.IN_PROGRESS_ITEM)),
                String.join(",", values(refs, RefKind.BLOCKED_ITEM)),
                String.join(",", values(refs, RefKind.READ_FILE)),
                String.join(",", values(refs, RefKind.WORKSPACE_CHANGE)),
                String.join(",", values(refs, RefKind.VALIDATION)),
                String.join(",", values(refs, RefKind.DIFF)),
                String.join(",", failureSummaries),
                deliveryIntent,
                String.join(",", missing),
                Integer.toString(remainingModelCalls),
                Integer.toString(remainingToolCalls),
                Integer.toString(remainingPercent),
                Boolean.toString(reserveActive)));
        return new CodingWorkProjection(
                SCHEMA_VERSION,
                taskContractDigest,
                taskIntent,
                phase,
                values(refs, RefKind.DONE_ITEM),
                values(refs, RefKind.IN_PROGRESS_ITEM),
                values(refs, RefKind.BLOCKED_ITEM),
                values(refs, RefKind.READ_FILE),
                values(refs, RefKind.WORKSPACE_CHANGE),
                values(refs, RefKind.VALIDATION),
                values(refs, RefKind.DIFF),
                failureSummaries,
                deliveryIntent,
                missing,
                remainingModelCalls,
                remainingToolCalls,
                remainingPercent,
                reserveActive,
                digest);
    }

    private void collect(
            ToolCall call, Map<RefKind, LinkedHashSet<String>> refs, Map<String, Integer> failureClusters) {
        Map<String, Object> data = call.result()
                .map(result -> result.structuredData())
                .orElseGet(
                        () -> call.error().map(error -> error.error().details()).orElse(Map.of()));
        if (call.status() == ToolCallStatus.COMPLETED && READ_TOOLS.contains(call.toolName())) {
            add(
                    refs.get(RefKind.READ_FILE),
                    reference("read", text(data, "path", call.toolName()), text(data, "contentVersion", "unknown")));
        }
        addChange(data.get("changeSetId"), refs.get(RefKind.WORKSPACE_CHANGE));
        addChange(data.get("fileChangeSetId"), refs.get(RefKind.WORKSPACE_CHANGE));
        if (data.get("changeSetIds") instanceof List<?> values) {
            values.stream()
                    .limit(CodingWorkProjection.MAXIMUM_REFERENCES_PER_KIND)
                    .forEach(value -> addChange(value, refs.get(RefKind.WORKSPACE_CHANGE)));
        }
        if (call.toolName().equals("execution.run") || call.toolName().equals("execution_run")) {
            String family = evidenceFamily(data);
            String status = text(data, "status", call.status().name());
            String semantic = text(data, "semanticOutcome", "UNKNOWN");
            if (family.equals("BUILD") || family.equals("TEST")) {
                add(
                        refs.get(RefKind.VALIDATION),
                        reference("validation", call.id().value(), family, status, semantic));
            }
            if (family.equals("DIFF") && (status.equals("SUCCEEDED") || semantic.equals("EXPECTED_VARIANT"))) {
                add(refs.get(RefKind.DIFF), reference("diff", call.id().value(), status, semantic));
            }
        }
        if (call.status() == ToolCallStatus.FAILED || data.containsKey("failureCategory")) {
            String stableCode = token(text(data, "stableFailureCode", "UNCLASSIFIED_FAILURE"));
            String resource = token(text(data, "resourceClass", "UNKNOWN"));
            failureClusters.merge(stableCode + ":" + resource, 1, Integer::sum);
        }
    }

    private static CodingWorkPhase phase(
            CodingTaskIntent intent,
            CodingDeliveryEvidenceLedger.Snapshot snapshot,
            Set<String> blockedItems,
            List<String> missing) {
        if (!blockedItems.isEmpty()
                || (snapshot.has(CodingDeliveryEvidenceKind.BLOCKER_CONFIRMED)
                        && snapshot.has(CodingDeliveryEvidenceKind.VALIDATION_FAILED)
                        && !snapshot.has(CodingDeliveryEvidenceKind.VALIDATION_PASSED))) {
            return CodingWorkPhase.BLOCKED;
        }
        if (intent == CodingTaskIntent.ANALYZE || intent == CodingTaskIntent.REVIEW) {
            return snapshot.has(CodingDeliveryEvidenceKind.READ_ONLY_INSPECTION)
                    ? CodingWorkPhase.REVIEW
                    : CodingWorkPhase.ORIENT;
        }
        if (snapshot.has(CodingDeliveryEvidenceKind.DIFF_INSPECTION)
                && snapshot.has(CodingDeliveryEvidenceKind.VALIDATION_ATTEMPT)
                && missing.isEmpty()) {
            return CodingWorkPhase.DELIVER;
        }
        if (snapshot.has(CodingDeliveryEvidenceKind.VALIDATION_ATTEMPT)) return CodingWorkPhase.REVIEW;
        if (snapshot.has(CodingDeliveryEvidenceKind.WORKSPACE_CHANGE)) return CodingWorkPhase.VERIFY;
        if (snapshot.has(CodingDeliveryEvidenceKind.READ_ONLY_INSPECTION)) {
            return intent == CodingTaskIntent.CHANGE || intent == CodingTaskIntent.CREATE
                    ? CodingWorkPhase.CHANGE
                    : CodingWorkPhase.PLAN;
        }
        return CodingWorkPhase.ORIENT;
    }

    private static List<String> missingEvidence(
            CodingTaskIntent intent, CodingDeliveryEvidenceLedger.Snapshot snapshot) {
        List<String> missing = new ArrayList<>();
        if (intent == CodingTaskIntent.CHANGE || intent == CodingTaskIntent.CREATE) {
            if (!snapshot.has(CodingDeliveryEvidenceKind.WORKSPACE_CHANGE)
                    && !snapshot.has(CodingDeliveryEvidenceKind.NO_CHANGE_JUSTIFICATION)) {
                missing.add("WORKSPACE_CHANGE");
            }
            if (!snapshot.has(CodingDeliveryEvidenceKind.VALIDATION_ATTEMPT)) missing.add("VALIDATION_ATTEMPT");
            if (!snapshot.has(CodingDeliveryEvidenceKind.DIFF_INSPECTION)) missing.add("DIFF_INSPECTION");
            if (snapshot.has(CodingDeliveryEvidenceKind.VALIDATION_FAILED)
                    && !snapshot.has(CodingDeliveryEvidenceKind.VALIDATION_PASSED)) {
                missing.add("VALIDATION_PASSED");
            }
        } else if ((intent == CodingTaskIntent.ANALYZE || intent == CodingTaskIntent.REVIEW)
                && !snapshot.has(CodingDeliveryEvidenceKind.READ_ONLY_INSPECTION)) {
            missing.add("READ_ONLY_EVIDENCE");
        }
        return List.copyOf(missing);
    }

    private String taskContractDigest(AgentRun run, CodingTaskIntent intent) {
        AgentMessage firstUser = state.messages(run.id()).stream()
                .filter(message -> message.role() == MessageRole.USER)
                .min(Comparator.comparingLong(AgentMessage::sequence))
                .orElse(null);
        return firstUser == null
                ? PolicyDigest.sha256Fields(List.of("coding-task-contract-v1", intent.name(), "missing-user-message"))
                : PolicyDigest.sha256Fields(List.of(
                        "coding-task-contract-v1",
                        intent.name(),
                        firstUser.id().value(),
                        firstUser.contents().toString()));
    }

    private static String evidenceFamily(Map<String, Object> data) {
        String declared = text(data, "declaredOperationFamily", text(data, "operationFamily", "UNKNOWN"));
        String effective = text(data, "effectiveOperationFamily", text(data, "commandOperation", declared));
        return effective.equals("UNKNOWN") ? declared : effective;
    }

    private static void addChange(Object value, Set<String> target) {
        if (value instanceof String text && !text.isBlank()) add(target, reference("change", text));
    }

    private static void add(Set<String> values, String value) {
        if (values.contains(value)) return;
        if (values.size() >= CodingWorkProjection.MAXIMUM_REFERENCES_PER_KIND) {
            var iterator = values.iterator();
            iterator.next();
            iterator.remove();
        }
        values.add(value);
    }

    private static List<String> values(Map<RefKind, LinkedHashSet<String>> values, RefKind kind) {
        return List.copyOf(values.get(kind));
    }

    private static String reference(String kind, String... values) {
        return kind + ":" + PolicyDigest.sha256Fields(List.of(values));
    }

    private static String text(Map<String, Object> data, String key, String fallback) {
        Object value = data.get(key);
        return value instanceof String text && !text.isBlank() && text.length() <= 512 ? text : fallback;
    }

    private static String token(String value) {
        String normalized = value.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9_.:-]", "_");
        return normalized.substring(0, Math.min(96, normalized.length()));
    }

    private static int remaining(long limit, long used) {
        return Math.toIntExact(Math.min(Integer.MAX_VALUE, Math.max(0, limit - used)));
    }

    private static int percent(long remaining, long limit) {
        if (limit <= 0) return 100;
        return (int) Math.max(0, Math.min(100, (remaining / (double) limit) * 100));
    }

    private enum RefKind {
        DONE_ITEM,
        IN_PROGRESS_ITEM,
        BLOCKED_ITEM,
        READ_FILE,
        WORKSPACE_CHANGE,
        VALIDATION,
        DIFF,
        FAILURE
    }
}

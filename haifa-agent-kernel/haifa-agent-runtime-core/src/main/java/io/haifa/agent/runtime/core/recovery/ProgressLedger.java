package io.haifa.agent.runtime.core.recovery;

import io.haifa.agent.core.plan.AgentPlan;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallStatus;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Last 32 meaningful evidence summaries. Messages and failed-call counts are intentionally absent. */
public final class ProgressLedger {
    static final int MAXIMUM_EVIDENCE = 32;
    private final Deque<ProgressEvidence> evidence = new ArrayDeque<>();
    private String planDigest;
    private long childResults;

    public boolean observe(ToolCall call) {
        boolean changed = false;
        if (call.status() == ToolCallStatus.COMPLETED) {
            var result = call.result().orElseThrow();
            Map<String, Object> data = result.structuredData();
            changed |= addReference(
                    ProgressEvidence.Type.WORKSPACE_CHANGE, firstText(data, "changeSetId", "fileChangeSetId"));
            for (var artifact : result.artifacts()) {
                changed |= add(ProgressEvidence.Type.ARTIFACT_CHANGE, artifact.artifactId());
            }
            String family = firstText(data, "operationFamily").orElse("UNKNOWN");
            String status = firstText(data, "status").orElse("");
            if ((family.equals("TEST") || family.equals("BUILD") || family.equals("DIFF"))
                    && status.equals("SUCCEEDED")) {
                changed |= addDigest(ProgressEvidence.Type.VALIDATION_ADVANCE, validationDigest(call, family, status));
            }
        } else if (call.status() == ToolCallStatus.FAILED) {
            Map<String, Object> attributes =
                    call.error().map(value -> value.error().details()).orElse(Map.of());
            changed |= addReference(
                    ProgressEvidence.Type.WORKSPACE_CHANGE, firstText(attributes, "changeSetId", "fileChangeSetId"));
        }
        return changed;
    }

    public boolean observePlan(Optional<AgentPlan> plan) {
        String current = plan.map(value -> FailureFingerprint.digest(value.items().stream()
                        .map(item -> item.id() + ":" + item.status())
                        .toList()))
                .orElse(FailureFingerprint.digest(List.of("no-plan")));
        if (planDigest == null) {
            planDigest = current;
            return false;
        }
        if (planDigest.equals(current)) return false;
        planDigest = current;
        return addDigest(ProgressEvidence.Type.TODO_ADVANCE, current);
    }

    public boolean observeChildResults(long completedChildren) {
        if (completedChildren <= childResults) {
            childResults = Math.max(childResults, completedChildren);
            return false;
        }
        childResults = completedChildren;
        return add(ProgressEvidence.Type.CHILD_RESULT_AVAILABLE, Long.toString(completedChildren));
    }

    public boolean observeInteraction(String stableResponseId) {
        if (stableResponseId == null || stableResponseId.isBlank() || stableResponseId.length() > 256) {
            throw new IllegalArgumentException("stableResponseId must be a bounded non-blank value");
        }
        return add(ProgressEvidence.Type.INTERACTION_SUPPLIED, stableResponseId);
    }

    public String digest() {
        if (evidence.isEmpty()) return FailureFingerprint.digest(List.of("no-meaningful-progress"));
        return FailureFingerprint.digest(evidence.stream()
                .map(value -> value.type().name() + ":" + value.safeDigest())
                .toList());
    }

    public int size() {
        return evidence.size();
    }

    public boolean hasMeaningfulProgress() {
        return !evidence.isEmpty();
    }

    public List<ProgressEvidence> evidence() {
        return List.copyOf(evidence);
    }

    private boolean addReference(ProgressEvidence.Type type, Optional<String> reference) {
        return reference.map(value -> add(type, value)).orElse(false);
    }

    private boolean add(ProgressEvidence.Type type, String stableReference) {
        return addDigest(type, FailureFingerprint.digest(List.of(stableReference)));
    }

    private boolean addDigest(ProgressEvidence.Type type, String digest) {
        ProgressEvidence next = new ProgressEvidence(type, digest);
        if (evidence.contains(next)) return false;
        evidence.addLast(next);
        while (evidence.size() > MAXIMUM_EVIDENCE) evidence.removeFirst();
        return true;
    }

    private static String validationDigest(ToolCall call, String family, String status) {
        Map<String, Object> arguments = call.arguments().values();
        return FailureFingerprint.digest(List.of(
                call.toolName(),
                call.toolVersion(),
                family,
                status,
                text(arguments.get("command")),
                text(arguments.get("workdir"))));
    }

    private static String text(Object value) {
        return value instanceof String text ? text : "";
    }

    private static Optional<String> firstText(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            Object value = values.get(key);
            if (value instanceof String text && !text.isBlank() && text.length() <= 256) {
                return Optional.of(text);
            }
        }
        return Optional.empty();
    }
}

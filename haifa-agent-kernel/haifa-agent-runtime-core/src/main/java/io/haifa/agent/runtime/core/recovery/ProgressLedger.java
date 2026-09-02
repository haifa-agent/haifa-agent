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
    static final String DIGEST_VERSION = "progress/2";
    private static final int MAXIMUM_REFERENCES_PER_RESULT = 256;
    private final Deque<ProgressEvidence> evidence = new ArrayDeque<>();
    private String planDigest;
    private String latestWorkspaceDigest = FailureFingerprint.digest(List.of("workspace-baseline"));
    private long childResults;

    public boolean observe(ToolCall call) {
        boolean changed = false;
        if (call.status() == ToolCallStatus.COMPLETED) {
            var result = call.result().orElseThrow();
            Map<String, Object> data = result.structuredData();
            changed |= addWorkspaceReference(
                    ProgressEvidence.Type.WORKSPACE_CHANGE,
                    firstText(data, "path", "patchSha256", "mutationId", "source", "changeSetId"));
            changed |= addWorkspaceReferences(data.get("changeSetIds"));
            for (var artifact : result.artifacts()) {
                changed |= add(ProgressEvidence.Type.ARTIFACT_CHANGE, artifact.artifactId());
            }
            changed |= addReference(ProgressEvidence.Type.ARTIFACT_CHANGE, firstText(data, "artifactRef"));
            changed |= addReferences(ProgressEvidence.Type.ARTIFACT_CHANGE, data.get("artifactRefs"));
            changed |= firstText(data, "validationAttemptRef")
                    .map(reference -> addDigest(
                            ProgressEvidence.Type.VALIDATION_ADVANCE,
                            FailureFingerprint.digest(List.of(reference, latestWorkspaceDigest))))
                    .orElse(false);
        } else if (call.status() == ToolCallStatus.FAILED) {
            Map<String, Object> attributes =
                    call.error().map(value -> value.error().details()).orElse(Map.of());
            changed |= addWorkspaceReference(
                    ProgressEvidence.Type.WORKSPACE_CHANGE, firstText(attributes, "path", "source", "changeSetId"));
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
        if (evidence.isEmpty()) return FailureFingerprint.digest(List.of(DIGEST_VERSION, "no-meaningful-progress"));
        List<String> facts = new java.util.ArrayList<>();
        facts.add(DIGEST_VERSION);
        facts.addAll(evidence.stream()
                .map(value -> value.type().name() + ":" + value.safeDigest())
                .toList());
        return FailureFingerprint.digest(facts);
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

    private boolean addReferences(ProgressEvidence.Type type, Object references) {
        if (!(references instanceof List<?> values)) return false;
        boolean changed = false;
        int observed = 0;
        for (Object value : values) {
            if (++observed > MAXIMUM_REFERENCES_PER_RESULT) break;
            if (value instanceof String text && !text.isBlank() && text.length() <= 256) {
                changed |= add(type, text);
            }
        }
        return changed;
    }

    private boolean addWorkspaceReference(ProgressEvidence.Type type, Optional<String> reference) {
        return reference.map(value -> addWorkspace(type, value)).orElse(false);
    }

    private boolean addWorkspaceReferences(Object references) {
        if (!(references instanceof List<?> values)) return false;
        boolean changed = false;
        int observed = 0;
        for (Object value : values) {
            if (++observed > MAXIMUM_REFERENCES_PER_RESULT) break;
            if (value instanceof String text && !text.isBlank() && text.length() <= 256) {
                changed |= addWorkspace(ProgressEvidence.Type.WORKSPACE_CHANGE, text);
            }
        }
        return changed;
    }

    private boolean addWorkspace(ProgressEvidence.Type type, String stableReference) {
        String digest = FailureFingerprint.digest(List.of(stableReference));
        boolean changed = addDigest(type, digest);
        if (changed) latestWorkspaceDigest = digest;
        return changed;
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

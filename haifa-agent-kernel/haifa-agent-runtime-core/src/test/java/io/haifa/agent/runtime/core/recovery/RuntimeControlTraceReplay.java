package io.haifa.agent.runtime.core.recovery;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic reducer for the safe control fields of production Runtime events. It intentionally
 * has no access to prompts, model responses, credentials, commands, stderr, or host paths.
 */
public final class RuntimeControlTraceReplay {
    public Snapshot replay(List<SafeEvent> events) {
        Objects.requireNonNull(events, "events must not be null");
        String phase = "WORKING";
        int maximumFailureClusterAttempts = 0;
        int completionRepairAttempts = 0;
        int remainingPercent = 100;
        int meaningfulProgressEvents = 0;
        int nonReplayableOutcomeUnknown = 0;
        boolean checkpointRestored = false;
        boolean interactionContinued = false;
        boolean atomicityPassed = true;
        String terminationReason = "NONE";
        Set<String> evidenceCodes = new LinkedHashSet<>();
        for (SafeEvent event : List.copyOf(events)) {
            Map<String, Object> data = event.data();
            switch (event.type()) {
                case "tool.failure-cluster-updated" ->
                    maximumFailureClusterAttempts =
                            Math.max(maximumFailureClusterAttempts, integer(data, "attempts", 0));
                case "tool.recovery-strategy-required" -> phase = "RECOVERING";
                case "completion.deferred" -> {
                    completionRepairAttempts = Math.max(completionRepairAttempts, integer(data, "attempt", 0));
                    phase = safePhase(data.get("phase"), phase);
                    strings(data.get("evidenceCodes")).forEach(evidenceCodes::add);
                }
                case "delivery.evidence-updated" ->
                    strings(data.get("evidenceCodes")).forEach(evidenceCodes::add);
                case "loop.budget-snapshot" ->
                    remainingPercent = Math.max(0, Math.min(100, integer(data, "remainingPercent", remainingPercent)));
                case "loop.progress-observed" -> meaningfulProgressEvents++;
                case "execution.failed" -> {
                    if ("UNKNOWN".equals(String.valueOf(data.get("status")))) {
                        nonReplayableOutcomeUnknown++;
                    }
                }
                case "checkpoint.restored" -> checkpointRestored = true;
                case "interaction.response-applied", "approval.response-applied" -> interactionContinued = true;
                case "verification.side-effect-evaluated" -> atomicityPassed &= Boolean.TRUE.equals(data.get("passed"));
                case "run.completed" -> {
                    phase = "COMPLETED";
                    terminationReason = "COMPLETED";
                }
                case "run.failed", "run.cancelled", "run.timed-out", "run.structured-termination" -> {
                    phase = "FAILED";
                    terminationReason = boundedText(
                            data.get("reason"), event.type().substring(4).toUpperCase());
                }
                default -> {
                    // Unknown safe events are forward-compatible and do not affect the reduced state.
                }
            }
        }
        return new Snapshot(
                phase,
                maximumFailureClusterAttempts,
                completionRepairAttempts,
                remainingPercent,
                meaningfulProgressEvents,
                Set.copyOf(evidenceCodes),
                terminationReason,
                nonReplayableOutcomeUnknown,
                checkpointRestored,
                interactionContinued,
                atomicityPassed);
    }

    private static int integer(Map<String, Object> data, String key, int fallback) {
        Object value = data.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static String safePhase(Object value, String fallback) {
        String phase = String.valueOf(value);
        return Set.of("WORKING", "RECOVERING", "VERIFYING", "WAITING").contains(phase) ? phase : fallback;
    }

    private static List<String> strings(Object raw) {
        if (!(raw instanceof List<?> values)) return List.of();
        return values.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(value -> value.matches("[A-Z][A-Z0-9_:]{0,127}"))
                .limit(64)
                .toList();
    }

    private static String boundedText(Object value, String fallback) {
        String text = String.valueOf(value);
        return text.matches("[A-Z][A-Z0-9_]{0,127}") ? text : fallback;
    }

    public record SafeEvent(String type, Map<String, Object> data) {
        public SafeEvent {
            type = Objects.requireNonNull(type, "type must not be null").strip();
            if (type.isEmpty() || type.length() > 128) throw new IllegalArgumentException("event type is invalid");
            data = Map.copyOf(Objects.requireNonNull(data, "data must not be null"));
        }
    }

    public record Snapshot(
            String phase,
            int maximumFailureClusterAttempts,
            int completionRepairAttempts,
            int remainingPercent,
            int meaningfulProgressEvents,
            Set<String> evidenceCodes,
            String terminationReason,
            int nonReplayableOutcomeUnknown,
            boolean checkpointRestored,
            boolean interactionContinued,
            boolean atomicityPassed) {
        public Snapshot {
            evidenceCodes = Set.copyOf(evidenceCodes);
        }
    }
}

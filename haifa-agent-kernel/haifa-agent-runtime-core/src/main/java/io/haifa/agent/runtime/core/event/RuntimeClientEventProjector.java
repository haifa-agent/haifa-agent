package io.haifa.agent.runtime.core.event;

import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.agent.runtime.core.storage.RunStateRepository;
import io.haifa.agent.runtime.core.storage.RuntimeEvent;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Typed allowlist projection from committed internal Journal entries to client events. */
public final class RuntimeClientEventProjector {
    private final RunStateRepository runs;

    public RuntimeClientEventProjector(RunStateRepository runs) {
        this.runs = Objects.requireNonNull(runs, "runs must not be null");
    }

    public Optional<AgentRunEvent> project(RuntimeEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        if (isKnownClientFact(event) && !"1".equals(event.eventSchemaVersion())) {
            throw new IllegalStateException("known client event has an unsupported schema version");
        }
        Projection projection =
                switch (event.type()) {
                    case "run.created" ->
                        new Projection(
                                "run.accepted",
                                new RunEventPayloads.RunLifecycle(
                                        "ACCEPTED", number(event.data(), "version", 0), "NONE"));
                    case "approval.requested", "interaction.requested" ->
                        interaction("interaction.requested", event, "PENDING", "REQUESTED");
                    case "approval.responded", "interaction.responded" ->
                        interaction(
                                "interaction.responded",
                                event,
                                "RESPONDED",
                                text(event.data(), "responseType", text(event.data(), "action", "submit")));
                    case "interaction.expired" ->
                        interaction(
                                "interaction.expired",
                                event,
                                "EXPIRED",
                                text(event.data(), "outcome", "INTERACTION_EXPIRED"));
                    case "interaction.invalidated" ->
                        interaction(
                                "interaction.invalidated",
                                event,
                                "INVALIDATED",
                                text(event.data(), "reasonCode", "INTERACTION_INVALIDATED"));
                    case "run.input.accepted" ->
                        new Projection(
                                "run.input.accepted",
                                new RunEventPayloads.RunInputLifecycle(
                                        requiredText(event.data(), "inputId"), "ACCEPTED", "PENDING_SAFE_POINT"));
                    case "run.input.applied" ->
                        new Projection(
                                "run.input.applied",
                                new RunEventPayloads.RunInputLifecycle(
                                        requiredText(event.data(), "inputId"),
                                        "APPLIED",
                                        text(event.data(), "applicationPoint", "BEFORE_ITERATION")));
                    case "model.call.started" -> model("model.call.started", event, "STARTED");
                    case "model.call.succeeded" -> model("model.call.succeeded", event, "SUCCEEDED");
                    case "model.call.failed" -> model("model.call.failed", event, "FAILED");
                    case "tool.requested" -> tool("tool.call.requested", event, "REQUESTED", "NONE");
                    case "tool.started" -> tool("tool.call.started", event, "STARTED", "NONE");
                    case "tool.succeeded", "tool.completed" -> tool("tool.call.succeeded", event, "SUCCEEDED", "NONE");
                    case "tool.failed", "tool.business-failed" ->
                        tool("tool.call.failed", event, "FAILED", "TOOL_FAILED");
                    case "tool.cancelled" -> tool("tool.call.cancelled", event, "CANCELLED", "TOOL_CANCELLED");
                    case "execution.completed" -> execution("execution.completed", event);
                    case "execution.failed" -> execution("execution.failed", event);
                    case "execution.cancelled" -> execution("execution.cancelled", event);
                    case "workspace.change-set.available" -> resource("workspace.change-set.available", event);
                    case "artifact.available" -> resource("artifact.available", event);
                    case "checkpoint.available" -> resource("checkpoint.available", event);
                    case "completion.deferred" ->
                        delivery(
                                "completion.deferred",
                                event,
                                text(event.data(), "phase", "RECOVERING"),
                                "COMPLETION_DEFERRED",
                                text(event.data(), "reasonCode", "DELIVERY_EVIDENCE_MISSING"),
                                texts(event.data(), "missingEvidence"),
                                integer(event.data(), "remainingPercent", 0),
                                integer(event.data(), "attempt", 0));
                    case "tool.recovery-strategy-required" ->
                        delivery(
                                "recovery.required",
                                event,
                                "RECOVERING",
                                "RECOVERY_REQUIRED",
                                text(event.data(), "directive", "RECOVERY_REQUIRED"),
                                List.of(),
                                0,
                                integer(event.data(), "attempts", 0));
                    case "loop.budget-snapshot" -> budgetThreshold(event);
                    default -> outputOrLifecycle(event);
                };
        if (projection == null) return Optional.empty();
        var run = runs.find(event.runId())
                .orElseThrow(() -> new IllegalStateException("client event references an unknown run"));
        return Optional.of(new AgentRunEvent(
                event.eventId(),
                projection.eventType(),
                event.eventSchemaVersion(),
                event.runId(),
                run.sessionId(),
                event.sequence(),
                new RunEventCursor(event.runId(), "1", OptionalLong.of(event.sequence())),
                event.occurredAt(),
                event.correlationId(),
                event.causationId(),
                projection.payload()));
    }

    private static boolean isKnownClientFact(RuntimeEvent event) {
        return event.type().startsWith("runtime.command-")
                || java.util.Set.of(
                                "tool.requested",
                                "tool.started",
                                "tool.succeeded",
                                "tool.completed",
                                "tool.failed",
                                "tool.business-failed",
                                "tool.cancelled",
                                "model.call.started",
                                "model.call.succeeded",
                                "model.call.failed",
                                "execution.completed",
                                "execution.failed",
                                "execution.cancelled",
                                "workspace.change-set.available",
                                "artifact.available",
                                "checkpoint.available")
                        .contains(event.type())
                || event.type().equals("completion.deferred")
                || event.type().equals("tool.recovery-strategy-required")
                || event.type().equals("loop.budget-snapshot")
                || event.type().equals("run.created")
                || event.type().equals("approval.requested")
                || event.type().equals("approval.responded")
                || event.type().equals("interaction.requested")
                || event.type().equals("interaction.responded")
                || event.type().equals("interaction.expired")
                || event.type().equals("interaction.invalidated")
                || event.type().equals("run.input.accepted")
                || event.type().equals("run.input.applied")
                || (event.type().startsWith("run.") && event.data().containsKey("status"));
    }

    private static Projection outputOrLifecycle(RuntimeEvent event) {
        if (event.type().startsWith("model.output.")) return null;
        if (event.type().startsWith("runtime.command-")) {
            return new Projection(
                    "runtime.command.resulted",
                    new RunEventPayloads.CommandResult(
                            requiredText(event.data(), "commandId"),
                            requiredText(event.data(), "commandType"),
                            event.type().substring("runtime.command-".length()).toUpperCase(Locale.ROOT)));
        }
        if (event.type().startsWith("run.")
                && !event.type().startsWith("run.input.")
                && event.data().containsKey("status")) {
            return new Projection(
                    "run.status.changed",
                    new RunEventPayloads.RunLifecycle(
                            requiredText(event.data(), "status"),
                            number(event.data(), "version", 0),
                            text(event.data(), "reasonCode", "NONE")));
        }
        return null;
    }

    private static Projection interaction(String eventType, RuntimeEvent event, String state, String actionOrReason) {
        return new Projection(
                eventType,
                new RunEventPayloads.InteractionLifecycle(
                        requiredText(event.data(), "requestId"), inferredKind(event), state, actionOrReason));
    }

    private static Projection model(String eventType, RuntimeEvent event, String status) {
        return new Projection(
                eventType,
                new RunEventPayloads.ModelLifecycle(
                        requiredText(event.data(), "modelCallId"),
                        requiredText(event.data(), "providerId"),
                        requiredText(event.data(), "modelId"),
                        text(event.data(), "status", status),
                        requiredPositiveInteger(event.data(), "iteration"),
                        requiredPositiveInteger(event.data(), "attempt"),
                        requiredNonNegativeNumber(event.data(), "inputTokens"),
                        requiredNonNegativeNumber(event.data(), "outputTokens"),
                        text(event.data(), "finishReason", ""),
                        requiredText(event.data(), "reasonCode")));
    }

    private static Projection tool(String eventType, RuntimeEvent event, String status, String reasonCode) {
        return new Projection(
                eventType,
                new RunEventPayloads.ToolLifecycle(
                        requiredText(event.data(), "toolCallId"),
                        text(event.data(), "displayName", text(event.data(), "toolName", "tool")),
                        text(event.data(), "status", status),
                        text(event.data(), "reasonCode", reasonCode),
                        text(event.data(), "targetSummary", ""),
                        text(event.data(), "resultRef", "")));
    }

    private static Projection execution(String eventType, RuntimeEvent event) {
        return new Projection(
                eventType,
                new RunEventPayloads.ExecutionLifecycle(
                        requiredText(event.data(), "executionId"),
                        requiredText(event.data(), "toolCallId"),
                        requiredText(event.data(), "status"),
                        text(event.data(), "commandSummary", "shell command"),
                        text(event.data(), "logicalWorkdir", "."),
                        text(event.data(), "streamKind", "MERGED"),
                        text(event.data(), "chunkOrRef", ""),
                        integer(event.data(), "exitCode"),
                        Boolean.TRUE.equals(event.data().get("truncated")),
                        text(event.data(), "fileChangeSetRef", "")));
    }

    private static Projection resource(String eventType, RuntimeEvent event) {
        return new Projection(
                eventType,
                new RunEventPayloads.ResourceAvailable(
                        requiredText(event.data(), "reference"),
                        requiredText(event.data(), "kind"),
                        requiredText(event.data(), "title"),
                        requiredText(event.data(), "status"),
                        text(event.data(), "action", "inspect")));
    }

    private static Projection budgetThreshold(RuntimeEvent event) {
        List<String> thresholds = texts(event.data(), "newThresholds");
        if (thresholds.isEmpty()) return null;
        return delivery(
                "budget.threshold-reached",
                event,
                "BUDGET",
                "BUDGET_THRESHOLD_REACHED",
                "REMAINING_" + thresholds.getFirst() + "_PERCENT",
                List.of(),
                integer(event.data(), "remainingPercent", 0),
                0);
    }

    private static Projection delivery(
            String eventType,
            RuntimeEvent event,
            String phase,
            String status,
            String reasonCode,
            List<String> missingEvidence,
            int remainingPercent,
            int attempt) {
        return new Projection(
                eventType,
                new RunEventPayloads.DeliveryLifecycle(
                        phase, status, reasonCode, missingEvidence, remainingPercent, attempt));
    }

    private static String inferredKind(RuntimeEvent event) {
        if (event.type().startsWith("approval.")) return "approval";
        return text(event.data(), "kind", "clarification");
    }

    private static String requiredText(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalStateException("known client event is missing a required safe field: " + key);
        }
        return text;
    }

    private static String text(Map<String, Object> data, String key, String fallback) {
        Object value = data.get(key);
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    private static long number(Map<String, Object> data, String key, long fallback) {
        Object value = data.get(key);
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private static int requiredPositiveInteger(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (!(value instanceof Number number) || number.intValue() < 1) {
            throw new IllegalStateException("known client event is missing a required positive field: " + key);
        }
        return number.intValue();
    }

    private static long requiredNonNegativeNumber(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (!(value instanceof Number number) || number.longValue() < 0) {
            throw new IllegalStateException("known client event is missing a required non-negative field: " + key);
        }
        return number.longValue();
    }

    private static Integer integer(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value instanceof Number number ? number.intValue() : null;
    }

    private static int integer(Map<String, Object> data, String key, int fallback) {
        Integer value = integer(data, key);
        return value == null ? fallback : value;
    }

    private static List<String> texts(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream()
                .map(String::valueOf)
                .filter(text -> !text.isBlank())
                .toList();
    }

    private record Projection(String eventType, AgentRunEvent.Payload payload) {}
}

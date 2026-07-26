package io.haifa.agent.store.jsonl;

import io.haifa.agent.runtime.core.storage.OutboxMessage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Maps known Outbox contracts by selecting safe fields instead of copying opaque payload maps. */
public final class SafeTranscriptMapperRegistry {
    private static final Set<String> STATUS_EVENTS = Set.of(
            "run.queued",
            "run.started",
            "run.pause-requested",
            "run.suspended",
            "run.resumed",
            "run.waiting-interaction",
            "run.waiting-approval",
            "run.completing",
            "run.completed",
            "run.failed",
            "run.cancelled",
            "run.timeout",
            "run.usage-recorded");

    private final Map<String, Function<OutboxMessage, Map<String, Object>>> mappers;

    public SafeTranscriptMapperRegistry(Map<String, Function<OutboxMessage, Map<String, Object>>> mappers) {
        this.mappers = Map.copyOf(Objects.requireNonNull(mappers, "mappers must not be null"));
    }

    public static SafeTranscriptMapperRegistry defaults() {
        Map<String, Function<OutboxMessage, Map<String, Object>>> mappings = new LinkedHashMap<>();
        mappings.put("run.created", message -> select(message.payload(), "profileVersion"));
        STATUS_EVENTS.forEach(event -> mappings.put(event, message -> select(message.payload(), "status", "version")));
        mappings.put("interaction.responded", message -> select(message.payload(), "requestId", "responseType"));
        mappings.put("interaction.requested", message -> select(message.payload(), "requestId", "kind"));
        mappings.put("interaction.expired", message -> select(message.payload(), "requestId", "outcome"));
        mappings.put(
                "approval.requested",
                message -> select(message.payload(), "requestId", "decisionId", "challenge", "semantics"));
        mappings.put("approval.responded", message -> select(message.payload(), "requestId", "responseType"));
        mappings.put(
                "approval.authority.verified",
                message -> select(message.payload(), "requestId", "responseId", "outcome", "reasonCode"));
        mappings.put(
                "approval.target.validated",
                message -> select(message.payload(), "requestId", "responseId", "outcome", "reasonCode"));
        mappings.put(
                "policy.decision.made",
                message -> select(message.payload(), "decisionId", "effect", "challenge", "reasonCode"));
        mappings.put("run.input.accepted", message -> select(message.payload(), "inputId", "kind"));
        mappings.put(
                "run.input.applied",
                message -> select(message.payload(), "inputId", "attemptId", "iteration", "safePoint"));
        mappings.put("runtime.command-accepted", message -> select(message.payload(), "commandId", "commandType"));
        mappings.put("runtime.command-rejected", message -> select(message.payload(), "commandId", "commandType"));
        return new SafeTranscriptMapperRegistry(mappings);
    }

    public SafeTranscriptEvent map(OutboxMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        if (!OutboxMessage.CURRENT_SCHEMA_VERSION.equals(message.schemaVersion())) {
            throw new TranscriptProjectionException(
                    TranscriptDiagnosticCode.UNSUPPORTED_SCHEMA,
                    "unsupported Outbox schema version for event " + message.id());
        }
        Function<OutboxMessage, Map<String, Object>> mapper = mappers.get(message.type());
        if (mapper == null) {
            throw new TranscriptProjectionException(
                    TranscriptDiagnosticCode.UNKNOWN_EVENT_TYPE,
                    "event type is not approved for transcript projection: " + message.type());
        }
        return new SafeTranscriptEvent(
                SafeTranscriptEvent.CURRENT_SCHEMA_VERSION,
                message.id(),
                message.runId().value(),
                message.sequence(),
                message.createdAt(),
                message.type(),
                mapper.apply(message));
    }

    public boolean supports(String eventType) {
        return mappers.containsKey(eventType);
    }

    private static Map<String, Object> select(Map<String, Object> source, String... names) {
        Map<String, Object> selected = new LinkedHashMap<>();
        for (String name : names) {
            Object value = source.get(name);
            if (value != null) selected.put(name, scalar(value, name));
        }
        return Map.copyOf(selected);
    }

    private static Object scalar(Object value, String field) {
        if (value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        throw new TranscriptProjectionException(
                TranscriptDiagnosticCode.UNSAFE_PAYLOAD, "non-scalar transcript field: " + field);
    }
}

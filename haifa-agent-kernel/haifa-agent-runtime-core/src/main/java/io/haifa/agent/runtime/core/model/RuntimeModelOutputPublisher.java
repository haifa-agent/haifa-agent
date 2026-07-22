package io.haifa.agent.runtime.core.model;

import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.AgentRunOutputEvent;
import io.haifa.agent.runtime.api.AgentRunOutputEventType;
import io.haifa.agent.runtime.api.AgentRunOutputListener;
import io.haifa.agent.runtime.api.RunOutputCursor;
import io.haifa.agent.runtime.core.storage.RuntimeEvent;
import io.haifa.agent.runtime.core.storage.RuntimeEventAppender;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Persists and publishes the safe public projection of model streaming events. */
public final class RuntimeModelOutputPublisher {
    private static final String PREFIX = "model.output.";
    private final RuntimeEventAppender events;
    private final TimeProvider time;
    private final CopyOnWriteArrayList<AgentRunOutputListener> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, Generation> failedGenerations = new ConcurrentHashMap<>();

    public RuntimeModelOutputPublisher(RuntimeEventAppender events, TimeProvider time) {
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
    }

    public void started(AgentRunId runId, String callId, int attempt, int iteration) {
        String key = key(runId, iteration);
        Generation failed = failedGenerations.remove(key);
        if (failed != null) {
            append(
                    runId,
                    failed.callId(),
                    failed.callId(),
                    failed.attempt(),
                    AgentRunOutputEventType.RUN_OUTPUT_SUPERSEDED,
                    "");
        }
        append(runId, callId, callId, attempt, AgentRunOutputEventType.RUN_OUTPUT_STARTED, "");
    }

    public void content(AgentRunId runId, String callId, int attempt, String delta) {
        append(runId, callId, callId, attempt, AgentRunOutputEventType.ASSISTANT_TEXT_DELTA, delta);
    }

    public void committed(AgentRunId runId, String callId, int attempt, int iteration) {
        failedGenerations.remove(key(runId, iteration));
        append(runId, callId, callId, attempt, AgentRunOutputEventType.ASSISTANT_TEXT_COMMITTED, "");
    }

    public void failed(AgentRunId runId, String callId, int attempt, int iteration) {
        failedGenerations.put(key(runId, iteration), new Generation(callId, attempt));
        append(runId, callId, callId, attempt, AgentRunOutputEventType.RUN_OUTPUT_FAILED, "");
    }

    public List<AgentRunOutputEvent> after(AgentRunId runId, RunOutputCursor after, int limit) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(after, "after must not be null");
        if (limit < 1 || limit > 10_000) throw new IllegalArgumentException("limit must be between 1 and 10000");
        return events.eventsFor(runId).stream()
                .filter(event -> event.sequence() > after.sequence())
                .filter(event -> event.type().startsWith(PREFIX))
                .limit(limit)
                .map(RuntimeModelOutputPublisher::project)
                .toList();
    }

    public void addListener(AgentRunOutputListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener must not be null"));
    }

    private void append(
            AgentRunId runId,
            String callId,
            String generationId,
            int attempt,
            AgentRunOutputEventType type,
            String text) {
        RuntimeEvent stored = events.append(
                runId,
                PREFIX + type.name().toLowerCase(java.util.Locale.ROOT),
                Map.of(
                        "modelCallId", callId,
                        "generationId", generationId,
                        "physicalAttempt", attempt,
                        "eventType", type.name(),
                        "textDelta", text),
                time.now());
        AgentRunOutputEvent projected = project(stored);
        listeners.forEach(listener -> {
            try {
                listener.onOutput(projected);
            } catch (RuntimeException ignored) {
                // Output observers are isolated from the AgentLoop. Persistent replay remains available.
            }
        });
    }

    private static AgentRunOutputEvent project(RuntimeEvent event) {
        Map<String, Object> data = event.data();
        return new AgentRunOutputEvent(
                event.runId(),
                String.valueOf(data.get("modelCallId")),
                String.valueOf(data.get("generationId")),
                ((Number) data.get("physicalAttempt")).intValue(),
                event.sequence(),
                AgentRunOutputEventType.valueOf(String.valueOf(data.get("eventType"))),
                String.valueOf(data.get("textDelta")),
                event.occurredAt());
    }

    private static String key(AgentRunId runId, int iteration) {
        return runId.value() + "|" + iteration;
    }

    private record Generation(String callId, int attempt) {}
}

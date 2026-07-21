package io.haifa.agent.runtime.core.storage;

import io.haifa.agent.core.checkpoint.Checkpoint;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.plan.AgentPlan;
import io.haifa.agent.core.reference.RunConfigurationSnapshotRef;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.step.AgentStep;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.runtime.api.RuntimeCommandResult;
import io.haifa.agent.runtime.core.attempt.AgentRunExecutionAttempt;
import io.haifa.agent.runtime.core.attempt.ExecutionAttemptId;
import io.haifa.agent.runtime.core.bootstrap.RuntimeConfigurationSnapshot;
import io.haifa.agent.runtime.core.checkpoint.RuntimeCheckpointState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Thread-safe deterministic store used by local embeddings and tests. */
public final class InMemoryRuntimeStore
        implements RunStateRepository,
                ExecutionAttemptRepository,
                RuntimeEventAppender,
                RuntimeOutboxPublisher,
                CheckpointRepository,
                IdempotencyRepository,
                RuntimeStateRepository,
                RuntimeUnitOfWork {

    private record Versioned<T>(T value, long version) {}

    private record StoredCheckpoint(Checkpoint checkpoint, RuntimeCheckpointState state) {}

    private final Map<AgentRunId, Versioned<AgentRun>> runs = new HashMap<>();
    private final Map<ExecutionAttemptId, Versioned<AgentRunExecutionAttempt>> attempts = new HashMap<>();
    private final Map<AgentRunId, List<RuntimeEvent>> events = new HashMap<>();
    private final List<OutboxMessage> outbox = new ArrayList<>();
    private final java.util.Set<String> publishedOutbox = new java.util.HashSet<>();
    private final java.util.Set<String> consumedOutbox = new java.util.HashSet<>();
    private final Map<AgentRunId, List<StoredCheckpoint>> checkpoints = new HashMap<>();
    private final Map<String, AgentRunId> idempotentRuns = new HashMap<>();
    private final java.util.Set<String> appliedCommands = new java.util.HashSet<>();
    private final Map<String, RuntimeCommandResult> commandResults = new HashMap<>();
    private final Map<AgentRunId, List<AgentMessage>> messages = new HashMap<>();
    private final Map<AgentRunId, List<AgentStep>> steps = new HashMap<>();
    private final Map<AgentRunId, List<ToolCall>> toolCalls = new HashMap<>();
    private final Map<AgentRunId, AgentPlan> plans = new HashMap<>();
    private final Map<AgentRunId, String> outputs = new ConcurrentHashMap<>();
    private final Map<String, RuntimeConfigurationSnapshot> configurations = new HashMap<>();

    @Override
    public synchronized void insert(AgentRun run) {
        if (runs.putIfAbsent(run.id(), new Versioned<>(run, run.version())) != null) {
            throw new IllegalStateException("run already exists: " + run.id().value());
        }
    }

    @Override
    public synchronized void save(AgentRun run, long expectedVersion) {
        Versioned<AgentRun> current = runs.get(run.id());
        if (current == null)
            throw new IllegalStateException("run does not exist: " + run.id().value());
        if (current.version() != expectedVersion) {
            throw new OptimisticLockException(
                    "run version conflict: expected " + expectedVersion + " but was " + current.version());
        }
        runs.put(run.id(), new Versioned<>(run, run.version()));
    }

    @Override
    public synchronized Optional<AgentRun> find(AgentRunId runId) {
        return Optional.ofNullable(runs.get(runId)).map(Versioned::value);
    }

    @Override
    public synchronized void insert(AgentRunExecutionAttempt attempt) {
        boolean activeExists = attempts.values().stream()
                .map(Versioned::value)
                .anyMatch(existing -> existing.runId().equals(attempt.runId())
                        && !existing.status().isTerminal());
        if (activeExists) throw new IllegalStateException("run already has an active attempt");
        if (attempts.putIfAbsent(attempt.attemptId(), new Versioned<>(attempt, attempt.version())) != null) {
            throw new IllegalStateException(
                    "attempt already exists: " + attempt.attemptId().value());
        }
    }

    @Override
    public synchronized void save(AgentRunExecutionAttempt attempt, long expectedVersion) {
        Versioned<AgentRunExecutionAttempt> current = attempts.get(attempt.attemptId());
        if (current == null)
            throw new IllegalStateException(
                    "attempt does not exist: " + attempt.attemptId().value());
        if (current.version() != expectedVersion) {
            throw new OptimisticLockException(
                    "attempt version conflict: expected " + expectedVersion + " but was " + current.version());
        }
        attempts.put(attempt.attemptId(), new Versioned<>(attempt, attempt.version()));
    }

    @Override
    public synchronized Optional<AgentRunExecutionAttempt> find(ExecutionAttemptId id) {
        return Optional.ofNullable(attempts.get(id)).map(Versioned::value);
    }

    @Override
    public synchronized Optional<AgentRunExecutionAttempt> activeFor(AgentRunId runId) {
        return attempts.values().stream()
                .map(Versioned::value)
                .filter(attempt ->
                        attempt.runId().equals(runId) && !attempt.status().isTerminal())
                .findFirst();
    }

    @Override
    public synchronized List<AgentRunExecutionAttempt> attemptsFor(AgentRunId runId) {
        return attempts.values().stream()
                .map(Versioned::value)
                .filter(attempt -> attempt.runId().equals(runId))
                .sorted(Comparator.comparingInt(AgentRunExecutionAttempt::attemptNumber))
                .toList();
    }

    @Override
    public synchronized RuntimeEvent append(
            AgentRunId runId, String type, Map<String, Object> data, Instant occurredAt) {
        List<RuntimeEvent> stream = events.computeIfAbsent(runId, ignored -> new ArrayList<>());
        RuntimeEvent event = new RuntimeEvent(runId, stream.size() + 1L, type, data, occurredAt);
        stream.add(event);
        return event;
    }

    @Override
    public synchronized List<RuntimeEvent> eventsFor(AgentRunId runId) {
        return List.copyOf(events.getOrDefault(runId, List.of()));
    }

    @Override
    public synchronized void append(OutboxMessage message) {
        outbox.add(message);
    }

    @Override
    public synchronized List<OutboxMessage> pending() {
        return outbox.stream()
                .filter(message -> !publishedOutbox.contains(message.id()))
                .toList();
    }

    @Override
    public synchronized void markPublished(String eventId) {
        boolean exists = outbox.stream().anyMatch(message -> message.id().equals(eventId));
        if (!exists) throw new IllegalArgumentException("unknown outbox event: " + eventId);
        publishedOutbox.add(eventId);
    }

    @Override
    public synchronized boolean markConsumed(String consumerId, String eventId) {
        if (consumerId == null || consumerId.isBlank() || eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("consumerId and eventId must not be blank");
        }
        return consumedOutbox.add(consumerId.trim() + "|" + eventId.trim());
    }

    @Override
    public synchronized void append(Checkpoint checkpoint, RuntimeCheckpointState state) {
        if (!checkpoint.runId().equals(state.runId())) {
            throw new IllegalArgumentException("checkpoint and state must belong to the same run");
        }
        List<StoredCheckpoint> stream = checkpoints.computeIfAbsent(checkpoint.runId(), ignored -> new ArrayList<>());
        if (checkpoint.sequence() != stream.size() + 1L) {
            throw new IllegalArgumentException("checkpoint sequence must be monotonic");
        }
        stream.add(new StoredCheckpoint(checkpoint, state));
    }

    @Override
    public synchronized Optional<Checkpoint> latest(AgentRunId runId) {
        List<StoredCheckpoint> stream = checkpoints.getOrDefault(runId, List.of());
        return stream.isEmpty()
                ? Optional.empty()
                : Optional.of(stream.getLast().checkpoint());
    }

    @Override
    public synchronized Optional<RuntimeCheckpointState> state(String checkpointId) {
        return checkpoints.values().stream()
                .flatMap(List::stream)
                .filter(item -> item.checkpoint().id().value().equals(checkpointId))
                .map(StoredCheckpoint::state)
                .findFirst();
    }

    @Override
    public synchronized List<Checkpoint> checkpointsFor(AgentRunId runId) {
        return checkpoints.getOrDefault(runId, List.of()).stream()
                .map(StoredCheckpoint::checkpoint)
                .toList();
    }

    @Override
    public synchronized Optional<AgentRunId> findRun(String callerScope, String operation, String key) {
        return Optional.ofNullable(idempotentRuns.get(idempotencyKey(callerScope, operation, key)));
    }

    @Override
    public synchronized AgentRunId recordRun(String callerScope, String operation, String key, AgentRunId runId) {
        return idempotentRuns.computeIfAbsent(idempotencyKey(callerScope, operation, key), ignored -> runId);
    }

    @Override
    public synchronized boolean markCommandApplied(String callerScope, String key) {
        return appliedCommands.add(callerScope + "|command|" + key);
    }

    @Override
    public synchronized Optional<RuntimeCommandResult> findCommandResult(String callerScope, String idempotencyKey) {
        return Optional.ofNullable(commandResults.get(callerScope + "|command-result|" + idempotencyKey));
    }

    @Override
    public synchronized void recordCommandResult(
            String callerScope, String idempotencyKey, RuntimeCommandResult result) {
        RuntimeCommandResult existing =
                commandResults.putIfAbsent(callerScope + "|command-result|" + idempotencyKey, result);
        if (existing != null && !existing.equals(result)) {
            throw new IllegalStateException("command result is already recorded with different content");
        }
    }

    @Override
    public synchronized void appendMessage(AgentMessage message) {
        AgentRunId runId =
                message.runId().orElseThrow(() -> new IllegalArgumentException("runtime message requires runId"));
        messages.computeIfAbsent(runId, ignored -> new ArrayList<>()).add(message);
    }

    @Override
    public synchronized void appendStep(AgentStep step) {
        steps.computeIfAbsent(step.runId(), ignored -> new ArrayList<>()).add(step);
    }

    @Override
    public synchronized void appendToolCall(ToolCall toolCall) {
        toolCalls
                .computeIfAbsent(toolCall.runId(), ignored -> new ArrayList<>())
                .add(toolCall);
    }

    @Override
    public synchronized void savePlan(AgentPlan plan) {
        plans.put(plan.runId(), plan);
    }

    @Override
    public synchronized List<AgentMessage> messages(AgentRunId runId) {
        return List.copyOf(messages.getOrDefault(runId, List.of()));
    }

    @Override
    public synchronized List<AgentStep> steps(AgentRunId runId) {
        return List.copyOf(steps.getOrDefault(runId, List.of()));
    }

    @Override
    public synchronized List<ToolCall> toolCalls(AgentRunId runId) {
        return List.copyOf(toolCalls.getOrDefault(runId, List.of()));
    }

    @Override
    public synchronized Optional<AgentPlan> plan(AgentRunId runId) {
        return Optional.ofNullable(plans.get(runId));
    }

    @Override
    public void saveOutput(AgentRunId runId, String output) {
        outputs.put(runId, output);
    }

    @Override
    public Optional<String> output(AgentRunId runId) {
        return Optional.ofNullable(outputs.get(runId));
    }

    @Override
    public synchronized void saveConfiguration(RuntimeConfigurationSnapshot configuration) {
        RuntimeConfigurationSnapshot existing =
                configurations.putIfAbsent(configuration.reference().snapshotId(), configuration);
        if (existing != null && !existing.equals(configuration)) {
            throw new IllegalStateException("configuration snapshot id collision");
        }
    }

    @Override
    public synchronized Optional<RuntimeConfigurationSnapshot> configuration(RunConfigurationSnapshotRef reference) {
        return Optional.ofNullable(configurations.get(reference.snapshotId()))
                .filter(configuration -> configuration.reference().contentHash().equals(reference.contentHash()));
    }

    @Override
    public synchronized <T> T execute(Supplier<T> work) {
        return work.get();
    }

    private static String idempotencyKey(String callerScope, String operation, String key) {
        return callerScope + "|" + operation + "|" + key;
    }
}

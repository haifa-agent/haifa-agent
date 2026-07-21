package io.haifa.agent.runtime.core.storage;

import io.haifa.agent.context.compression.ConversationSummary;
import io.haifa.agent.context.compression.ConversationSummaryRepository;
import io.haifa.agent.context.compression.SummaryId;
import io.haifa.agent.context.compression.SummaryVersion;
import io.haifa.agent.core.checkpoint.Checkpoint;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageCursor;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.plan.AgentPlan;
import io.haifa.agent.core.reference.AssetRef;
import io.haifa.agent.core.reference.RunConfigurationSnapshotRef;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.step.AgentStep;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.runtime.api.RuntimeCommandResult;
import io.haifa.agent.runtime.core.attempt.AgentRunExecutionAttempt;
import io.haifa.agent.runtime.core.attempt.ExecutionAttemptId;
import io.haifa.agent.runtime.core.bootstrap.RuntimeConfigurationSnapshot;
import io.haifa.agent.runtime.core.checkpoint.RuntimeCheckpointState;
import io.haifa.agent.runtime.core.tool.ToolResultAssetStore;
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
                ConversationSummaryRepository,
                ToolResultAssetStore,
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
    private final Map<io.haifa.agent.core.session.AgentSessionId, List<AgentMessage>> sessionMessages = new HashMap<>();
    private final Map<AgentMessageId, AgentMessage> messagesById = new HashMap<>();
    private final Map<AgentRunId, List<AgentStep>> steps = new HashMap<>();
    private final Map<AgentRunId, List<ToolCall>> toolCalls = new HashMap<>();
    private final Map<AgentRunId, AgentPlan> plans = new HashMap<>();
    private final Map<AgentRunId, String> outputs = new ConcurrentHashMap<>();
    private final Map<String, RuntimeConfigurationSnapshot> configurations = new HashMap<>();
    private final Map<io.haifa.agent.core.session.AgentSessionId, List<ConversationSummary>> summaries =
            new HashMap<>();
    private final Map<String, ToolResult> toolResultAssets = new HashMap<>();
    private boolean failNextToolResultAssetWrite;

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
    public synchronized AgentMessage appendSessionMessage(SessionMessageDraft draft) {
        if (messagesById.containsKey(draft.id())) {
            throw new IllegalStateException(
                    "message already exists: " + draft.id().value());
        }
        List<AgentMessage> stream = sessionMessages.computeIfAbsent(draft.sessionId(), ignored -> new ArrayList<>());
        long sequence = stream.isEmpty() ? 1L : Math.addExact(stream.getLast().sequence(), 1L);
        AgentMessage message = new AgentMessage(
                draft.id(),
                draft.sessionId(),
                draft.runId(),
                draft.parentMessageId(),
                draft.role(),
                draft.status(),
                draft.visibility(),
                sequence,
                draft.contents(),
                draft.metadata(),
                draft.createdAt());
        stream.add(message);
        messagesById.put(message.id(), message);
        message.runId().ifPresent(runId -> messages.computeIfAbsent(runId, ignored -> new ArrayList<>())
                .add(message));
        return message;
    }

    @Override
    public synchronized List<AgentMessage> messagesAfter(
            io.haifa.agent.core.session.AgentSessionId sessionId, MessageCursor cursor, int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        return sessionMessages.getOrDefault(sessionId, List.of()).stream()
                .filter(message -> message.sequence() > cursor.value())
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized RecentMessageWindow recentMessages(
            io.haifa.agent.core.session.AgentSessionId sessionId, MessageCursor atOrBefore, int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        List<AgentMessage> eligible = sessionMessages.getOrDefault(sessionId, List.of()).stream()
                .filter(message -> message.sequence() <= atOrBefore.value())
                .toList();
        List<AgentMessage> selected = eligible.subList(Math.max(0, eligible.size() - limit), eligible.size());
        if (selected.isEmpty()) {
            return new RecentMessageWindow(
                    sessionId, MessageCursor.BEFORE_FIRST, MessageCursor.BEFORE_FIRST, List.of());
        }
        return new RecentMessageWindow(
                sessionId, selected.getFirst().cursor(), selected.getLast().cursor(), selected);
    }

    @Override
    public synchronized Optional<MessageCursor> latestMessageCursor(
            io.haifa.agent.core.session.AgentSessionId sessionId) {
        List<AgentMessage> stream = sessionMessages.getOrDefault(sessionId, List.of());
        return stream.isEmpty()
                ? Optional.empty()
                : Optional.of(stream.getLast().cursor());
    }

    @Override
    public synchronized Optional<AgentMessage> message(AgentMessageId id) {
        return Optional.ofNullable(messagesById.get(id));
    }

    @Override
    public synchronized AgentMessage redactMessage(AgentMessageId id) {
        AgentMessage current = Optional.ofNullable(messagesById.get(id))
                .orElseThrow(() -> new IllegalArgumentException("unknown message: " + id.value()));
        AgentMessage redacted = new AgentMessage(
                current.id(),
                current.sessionId(),
                current.runId(),
                current.parentMessageId(),
                current.role(),
                MessageStatus.REDACTED,
                MessageVisibility.REDACTED,
                current.sequence(),
                List.of(new TextPart("[REDACTED]", "plain")),
                Map.of("redacted", true),
                current.createdAt());
        replaceMessage(current, redacted);
        invalidateContaining(current.sessionId(), id);
        return redacted;
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
    public synchronized AgentMessage saveFinalOutputAndMessage(
            AgentRunId runId, String output, SessionMessageDraft message) {
        if (!message.runId().equals(Optional.of(runId))) {
            throw new IllegalArgumentException("final message must belong to the output run");
        }
        if (outputs.containsKey(runId)) throw new IllegalStateException("run output is already stored");
        AgentMessage appended = appendSessionMessage(message);
        outputs.put(runId, output);
        return appended;
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
    public synchronized Optional<ConversationSummary> latestValid(
            io.haifa.agent.core.session.AgentSessionId sessionId) {
        return summaries.getOrDefault(sessionId, List.of()).stream()
                .filter(ConversationSummary::valid)
                .max(Comparator.comparingLong(summary -> summary.version().value()));
    }

    @Override
    public synchronized Optional<ConversationSummary> find(SummaryId id, SummaryVersion version) {
        return summaries.values().stream()
                .flatMap(List::stream)
                .filter(summary -> summary.id().equals(id) && summary.version().equals(version))
                .findFirst();
    }

    @Override
    public synchronized long latestVersion(io.haifa.agent.core.session.AgentSessionId sessionId) {
        List<ConversationSummary> stream = summaries.getOrDefault(sessionId, List.of());
        return stream.isEmpty() ? 0L : stream.getLast().version().value();
    }

    @Override
    public synchronized ConversationSummary compareAndSet(ConversationSummary summary, long expectedPreviousVersion) {
        List<ConversationSummary> stream = summaries.computeIfAbsent(summary.sessionId(), ignored -> new ArrayList<>());
        long actual = stream.isEmpty() ? 0L : stream.getLast().version().value();
        if (actual != expectedPreviousVersion || summary.version().value() != expectedPreviousVersion + 1L) {
            throw new OptimisticLockException(
                    "summary version conflict: expected " + expectedPreviousVersion + " but was " + actual);
        }
        stream.add(summary);
        return summary;
    }

    @Override
    public synchronized void invalidateContaining(
            io.haifa.agent.core.session.AgentSessionId sessionId, AgentMessageId messageId) {
        List<ConversationSummary> stream = summaries.getOrDefault(sessionId, List.of());
        for (int index = 0; index < stream.size(); index++) {
            ConversationSummary summary = stream.get(index);
            if (summary.valid() && summary.sourceMessageIds().contains(messageId)) {
                stream.set(index, summary.invalidate());
            }
        }
    }

    @Override
    public synchronized boolean coversValidSource(ConversationSummary summary, MessageCursor through) {
        if (!summary.valid() || summary.coveredThrough().compareTo(through) < 0) return false;
        return summary.sourceMessageIds().stream()
                .map(messagesById::get)
                .allMatch(message -> message != null
                        && message.sessionId().equals(summary.sessionId())
                        && message.status() == MessageStatus.COMPLETED
                        && message.visibility() != MessageVisibility.REDACTED);
    }

    @Override
    public synchronized AssetRef put(ToolCallId toolCallId, ToolResult result) {
        if (failNextToolResultAssetWrite) {
            failNextToolResultAssetWrite = false;
            throw new IllegalStateException("injected tool result asset write failure");
        }
        String assetId = "tool-result:" + toolCallId.value();
        ToolResult existing = toolResultAssets.putIfAbsent(assetId, result);
        if (existing != null && !existing.equals(result)) {
            throw new IllegalStateException("tool result asset id collision");
        }
        return new AssetRef(assetId, "application/vnd.haifa.tool-result", toolCallId.value() + ".result");
    }

    @Override
    public synchronized Optional<ToolResult> load(AssetRef reference) {
        return Optional.ofNullable(toolResultAssets.get(reference.assetId()));
    }

    public synchronized void failNextToolResultAssetWrite() {
        failNextToolResultAssetWrite = true;
    }

    @Override
    public synchronized <T> T execute(Supplier<T> work) {
        return work.get();
    }

    private static String idempotencyKey(String callerScope, String operation, String key) {
        return callerScope + "|" + operation + "|" + key;
    }

    private void replaceMessage(AgentMessage current, AgentMessage replacement) {
        List<AgentMessage> session = sessionMessages.get(current.sessionId());
        session.set(session.indexOf(current), replacement);
        current.runId().ifPresent(runId -> {
            List<AgentMessage> runMessages = messages.get(runId);
            runMessages.set(runMessages.indexOf(current), replacement);
        });
        messagesById.put(replacement.id(), replacement);
    }
}

package io.haifa.agent.runtime.core.checkpoint;

import io.haifa.agent.common.time.SystemTimeProvider;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.context.compression.ConversationSummaryRepository;
import io.haifa.agent.core.checkpoint.Checkpoint;
import io.haifa.agent.core.checkpoint.CheckpointType;
import io.haifa.agent.core.message.MessageCursor;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.runtime.core.storage.CheckpointRepository;
import io.haifa.agent.runtime.core.storage.RuntimeEventAppender;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CheckpointManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(CheckpointManager.class);

    private final CheckpointRepository repository;
    private final CheckpointPolicy policy;
    private final CheckpointSnapshotBuilder snapshotBuilder;
    private final ResumeCheckpointSelector selections;
    private final RuntimeStateRepository state;
    private final ConversationSummaryRepository summaries;
    private final MemoryCheckpointValidator memoryValidator;
    private final CapabilityCheckpointRegistry capabilityCheckpoints;
    private final TimeProvider time;
    private final RuntimeEventAppender events;

    public CheckpointManager(
            CheckpointRepository repository,
            CheckpointPolicy policy,
            CheckpointSnapshotBuilder snapshotBuilder,
            ResumeCheckpointSelector selections,
            RuntimeStateRepository state,
            ConversationSummaryRepository summaries,
            MemoryCheckpointValidator memoryValidator) {
        this(
                repository,
                policy,
                snapshotBuilder,
                selections,
                state,
                summaries,
                memoryValidator,
                CapabilityCheckpointRegistry.empty(),
                new SystemTimeProvider(),
                null);
    }

    public CheckpointManager(
            CheckpointRepository repository,
            CheckpointPolicy policy,
            CheckpointSnapshotBuilder snapshotBuilder,
            ResumeCheckpointSelector selections,
            RuntimeStateRepository state,
            ConversationSummaryRepository summaries,
            MemoryCheckpointValidator memoryValidator,
            CapabilityCheckpointRegistry capabilityCheckpoints) {
        this(
                repository,
                policy,
                snapshotBuilder,
                selections,
                state,
                summaries,
                memoryValidator,
                capabilityCheckpoints,
                new SystemTimeProvider(),
                null);
    }

    public CheckpointManager(
            CheckpointRepository repository,
            CheckpointPolicy policy,
            CheckpointSnapshotBuilder snapshotBuilder,
            ResumeCheckpointSelector selections,
            RuntimeStateRepository state,
            ConversationSummaryRepository summaries,
            MemoryCheckpointValidator memoryValidator,
            CapabilityCheckpointRegistry capabilityCheckpoints,
            TimeProvider time) {
        this(
                repository,
                policy,
                snapshotBuilder,
                selections,
                state,
                summaries,
                memoryValidator,
                capabilityCheckpoints,
                time,
                null);
    }

    public CheckpointManager(
            CheckpointRepository repository,
            CheckpointPolicy policy,
            CheckpointSnapshotBuilder snapshotBuilder,
            ResumeCheckpointSelector selections,
            RuntimeStateRepository state,
            ConversationSummaryRepository summaries,
            MemoryCheckpointValidator memoryValidator,
            CapabilityCheckpointRegistry capabilityCheckpoints,
            TimeProvider time,
            RuntimeEventAppender events) {
        this.repository = Objects.requireNonNull(repository);
        this.policy = Objects.requireNonNull(policy);
        this.snapshotBuilder = Objects.requireNonNull(snapshotBuilder);
        this.selections = Objects.requireNonNull(selections);
        this.state = Objects.requireNonNull(state);
        this.summaries = Objects.requireNonNull(summaries);
        this.memoryValidator = Objects.requireNonNull(memoryValidator);
        this.capabilityCheckpoints = Objects.requireNonNull(capabilityCheckpoints);
        this.time = Objects.requireNonNull(time);
        this.events = events;
    }

    public Optional<Checkpoint> capture(
            AgentRun run,
            int completedIteration,
            List<String> fingerprints,
            int forcedContextRebuildAttempts,
            CheckpointType type) {
        if (!policy.shouldCapture(run, completedIteration, type)) return Optional.empty();
        long started = System.nanoTime();
        long phaseStarted = started;
        long sequence = repository.latest(run.id()).map(Checkpoint::sequence).orElse(0L) + 1L;
        long latestMillis = elapsedMillis(phaseStarted);
        phaseStarted = System.nanoTime();
        var snapshot = snapshotBuilder.build(
                run, completedIteration, fingerprints, forcedContextRebuildAttempts, type, sequence);
        long snapshotMillis = elapsedMillis(phaseStarted);
        phaseStarted = System.nanoTime();
        repository.append(snapshot.checkpoint(), snapshot.state());
        long persistMillis = elapsedMillis(phaseStarted);
        phaseStarted = System.nanoTime();
        if (events != null) {
            events.append(
                    run.id(),
                    "checkpoint.available",
                    java.util.Map.of(
                            "reference",
                            snapshot.checkpoint().id().value(),
                            "kind",
                            "checkpoint",
                            "title",
                            "Checkpoint " + sequence,
                            "status",
                            "AVAILABLE",
                            "action",
                            "resume",
                            "checkpointType",
                            type.name()),
                    time.now());
        }
        long eventMillis = elapsedMillis(phaseStarted);
        LOGGER.info(
                "event=checkpoint.capture runId={} checkpointId={} sequence={} type={} latestMs={} snapshotMs={} persistMs={} eventMs={} totalMs={}",
                run.id().value(),
                snapshot.checkpoint().id().value(),
                sequence,
                type,
                latestMillis,
                snapshotMillis,
                persistMillis,
                eventMillis,
                elapsedMillis(started));
        return Optional.of(snapshot.checkpoint());
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    public Optional<RuntimeCheckpointState> restoreLatest(AgentRun run) {
        var selected = selections.consume(run.id());
        Optional<Checkpoint> checkpoint = selected.isPresent()
                ? repository.checkpointsFor(run.id()).stream()
                        .filter(value -> value.id().equals(selected.orElseThrow()))
                        .findFirst()
                : repository.latest(run.id());
        return checkpoint.flatMap(value -> repository.state(value.id().value()).map(restored -> {
            validateState(run, restored);
            if (!value.stateHash().equals(RuntimeCheckpointStateHasher.digest(restored))) {
                throw new CheckpointRestoreException(
                        CheckpointRestoreFailure.CHECKPOINT_HASH_INVALID,
                        "checkpoint state hash does not match the stored state");
            }
            var configuration = state.configuration(run.configurationSnapshot()).orElseThrow();
            capabilityCheckpoints.validateAndRestore(
                    run, configuration.capabilities(), restored.capabilityCheckpoints(), time.now());
            return restored;
        }));
    }

    public void validateState(AgentRun run, RuntimeCheckpointState checkpoint) {
        if (!checkpoint.runId().equals(run.id())
                || !checkpoint.sessionId().equals(run.sessionId())
                || !checkpoint.tenant().equals(run.tenant())
                || !checkpoint.principal().equals(run.principal())) {
            throw new CheckpointRestoreException(
                    CheckpointRestoreFailure.RUN_OR_OWNER_MISMATCH,
                    "checkpoint does not belong to the run session owner");
        }
        var configuration = state.configuration(run.configurationSnapshot())
                .orElseThrow(() -> new CheckpointRestoreException(
                        CheckpointRestoreFailure.FROZEN_CONFIGURATION_MISMATCH,
                        "frozen run configuration is unavailable"));
        if (!checkpoint.configurationSnapshot().equals(run.configurationSnapshot())
                || !checkpoint
                        .modelConfigurationDigest()
                        .equals(configuration.model().configurationDigest())) {
            throw new CheckpointRestoreException(
                    CheckpointRestoreFailure.FROZEN_CONFIGURATION_MISMATCH,
                    "checkpoint frozen model configuration does not match the run");
        }
        MessageCursor latest = state.latestMessageCursor(run.sessionId()).orElse(MessageCursor.BEFORE_FIRST);
        if (checkpoint.sessionMessageCursor().compareTo(latest) > 0) {
            throw new CheckpointRestoreException(
                    CheckpointRestoreFailure.SESSION_CURSOR_INVALID,
                    "checkpoint session cursor is no longer available");
        }
        checkpoint.activeSummary().ifPresent(reference -> {
            var summary = summaries
                    .find(reference.id(), reference.version())
                    .filter(value -> value.valid()
                            && value.sessionId().equals(run.sessionId())
                            && value.coveredThrough().equals(reference.coveredThrough()))
                    .orElseThrow(() -> new CheckpointRestoreException(
                            CheckpointRestoreFailure.SUMMARY_INVALID, "checkpoint summary is unavailable or invalid"));
            if (!summaries.coversValidSource(summary, reference.coveredThrough())) {
                throw new CheckpointRestoreException(
                        CheckpointRestoreFailure.SUMMARY_INVALID,
                        "checkpoint summary source facts were changed or redacted");
            }
        });
        var authoritativeTools = state.toolCalls(run.id());
        boolean toolsValid = checkpoint.toolCalls().stream().allMatch(reference -> authoritativeTools.stream()
                .anyMatch(call -> call.id().equals(reference.toolCallId())
                        && call.providerCorrelationId().equals(reference.providerCorrelationId())
                        && call.idempotencyKey().equals(reference.idempotencyKey())
                        && compatibleToolProgress(reference, call)));
        if (!toolsValid) {
            throw new CheckpointRestoreException(
                    CheckpointRestoreFailure.TOOL_STATE_INVALID,
                    "checkpoint tool state does not match authoritative tool calls");
        }
        var authoritativeContinuations = state.modelContinuations(run.id());
        boolean continuationsValid = checkpoint.modelContinuations().stream()
                .allMatch(reference -> authoritativeContinuations.stream()
                        .anyMatch(record -> record.reference().equals(reference)));
        if (!continuationsValid
                || authoritativeContinuations.size()
                        < checkpoint.modelContinuations().size()) {
            throw new CheckpointRestoreException(
                    CheckpointRestoreFailure.CAPABILITY_STATE_INVALID,
                    "checkpoint model continuation is unavailable or corrupt");
        }
        for (var reference : checkpoint.modelContinuations()) {
            var record = authoritativeContinuations.stream()
                    .filter(value -> value.reference().equals(reference))
                    .findFirst()
                    .orElseThrow();
            try {
                state.resolveContinuation(
                        record.assistantMessageId(), configuration.model(), record.toolCorrelationIds());
            } catch (io.haifa.agent.runtime.core.model.continuation.ModelContinuationException exception) {
                throw new CheckpointRestoreException(
                        CheckpointRestoreFailure.CAPABILITY_STATE_INVALID,
                        "checkpoint model continuation validation failed: " + exception.failure(),
                        exception);
            }
        }
        var authoritativeSkills = state.skillActivations(run.id()).stream()
                .map(value -> new SkillCheckpointRef(
                        value.binding().alias(),
                        value.binding().coordinate(),
                        value.binding().registrationDigest(),
                        value.activatedAt()))
                .toList();
        if (!authoritativeSkills.equals(checkpoint.skillActivations())) {
            throw new CheckpointRestoreException(
                    CheckpointRestoreFailure.CAPABILITY_STATE_INVALID,
                    "checkpoint Skill activation state is unavailable or does not match exact frozen bindings");
        }
        memoryValidator.validate(run, checkpoint);
    }

    private static boolean compatibleToolProgress(ToolCheckpointRef reference, io.haifa.agent.core.tool.ToolCall call) {
        if (terminal(reference.status())) {
            return call.status() == reference.status() && call.version() == reference.version();
        }
        return call.version() >= reference.version();
    }

    private static boolean terminal(io.haifa.agent.core.tool.ToolCallStatus status) {
        return switch (status) {
            case COMPLETED, FAILED, DENIED, CANCELLED, TIMEOUT -> true;
            default -> false;
        };
    }
}

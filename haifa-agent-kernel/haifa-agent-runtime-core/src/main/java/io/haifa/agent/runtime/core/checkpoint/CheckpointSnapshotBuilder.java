package io.haifa.agent.runtime.core.checkpoint;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.context.compaction.ConversationSummaryRepository;
import io.haifa.agent.core.checkpoint.Checkpoint;
import io.haifa.agent.core.checkpoint.CheckpointId;
import io.haifa.agent.core.checkpoint.CheckpointStatus;
import io.haifa.agent.core.checkpoint.CheckpointType;
import io.haifa.agent.core.message.MessageCursor;
import io.haifa.agent.core.reference.CheckpointPayloadRef;
import io.haifa.agent.core.reference.InteractionRequestRef;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.runtime.core.interaction.InteractionPort;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CheckpointSnapshotBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger(CheckpointSnapshotBuilder.class);

    public record Snapshot(Checkpoint checkpoint, RuntimeCheckpointState state) {}

    private final IdentifierGenerator ids;
    private final TimeProvider time;
    private final RuntimeStateRepository state;
    private final ConversationSummaryRepository summaries;
    private final InteractionPort interactions;
    private final CapabilityCheckpointRegistry capabilityCheckpoints;

    public CheckpointSnapshotBuilder(
            IdentifierGenerator ids,
            TimeProvider time,
            RuntimeStateRepository state,
            ConversationSummaryRepository summaries,
            InteractionPort interactions) {
        this(ids, time, state, summaries, interactions, CapabilityCheckpointRegistry.empty());
    }

    public CheckpointSnapshotBuilder(
            IdentifierGenerator ids,
            TimeProvider time,
            RuntimeStateRepository state,
            ConversationSummaryRepository summaries,
            InteractionPort interactions,
            CapabilityCheckpointRegistry capabilityCheckpoints) {
        this.ids = Objects.requireNonNull(ids);
        this.time = Objects.requireNonNull(time);
        this.state = Objects.requireNonNull(state);
        this.summaries = Objects.requireNonNull(summaries);
        this.interactions = Objects.requireNonNull(interactions);
        this.capabilityCheckpoints = Objects.requireNonNull(capabilityCheckpoints);
    }

    public Snapshot build(
            AgentRun run,
            int completedIteration,
            List<String> fingerprints,
            int forcedContextRebuildAttempts,
            CheckpointType type,
            long sequence) {
        long started = System.nanoTime();
        String id = ids.nextValue();
        long phaseStarted = System.nanoTime();
        var configuration = state.configuration(run.configurationSnapshot())
                .orElseThrow(() -> new IllegalStateException("run configuration snapshot is unavailable"));
        long configurationMillis = elapsedMillis(phaseStarted);
        phaseStarted = System.nanoTime();
        var summary = summaries
                .latestValid(run.sessionId())
                .map(value -> new SummaryCheckpointRef(value.id(), value.version(), value.coveredThrough()));
        long summaryMillis = elapsedMillis(phaseStarted);
        phaseStarted = System.nanoTime();
        var toolReferences = state.toolCalls(run.id()).stream()
                .map(call -> new ToolCheckpointRef(
                        call.id(), call.providerCorrelationId(), call.idempotencyKey(), call.status(), call.version()))
                .toList();
        long toolReferencesMillis = elapsedMillis(phaseStarted);
        phaseStarted = System.nanoTime();
        var assets = state.toolCalls(run.id()).stream()
                .flatMap(call -> call.result().stream())
                .flatMap(result -> result.assets().stream())
                .distinct()
                .toList();
        long assetsMillis = elapsedMillis(phaseStarted);
        phaseStarted = System.nanoTime();
        var pendingInteraction = interactions
                .pending(run.id())
                .map(value -> new InteractionRequestRef(value.id().value(), value.type()));
        long interactionMillis = elapsedMillis(phaseStarted);
        phaseStarted = System.nanoTime();
        var memorySelection = state.memorySelection(run.id())
                .orElse(io.haifa.agent.runtime.core.storage.RuntimeMemorySelection.EMPTY);
        long memoryMillis = elapsedMillis(phaseStarted);
        var capturedAt = time.now();
        phaseStarted = System.nanoTime();
        var capabilityReferences = capabilityCheckpoints.capture(run, configuration.capabilities(), id, capturedAt);
        long capabilitiesMillis = elapsedMillis(phaseStarted);
        phaseStarted = System.nanoTime();
        var modelContinuations = state.modelContinuations(run.id()).stream()
                .map(value -> value.reference())
                .toList();
        long continuationsMillis = elapsedMillis(phaseStarted);
        phaseStarted = System.nanoTime();
        var skillActivations = state.skillActivations(run.id()).stream()
                .map(value -> new SkillCheckpointRef(
                        value.binding().alias(),
                        value.binding().coordinate(),
                        value.binding().registrationDigest(),
                        value.activatedAt()))
                .toList();
        long skillsMillis = elapsedMillis(phaseStarted);
        phaseStarted = System.nanoTime();
        var messageCursor = state.latestMessageCursor(run.sessionId()).orElse(MessageCursor.BEFORE_FIRST);
        long cursorMillis = elapsedMillis(phaseStarted);
        phaseStarted = System.nanoTime();
        RuntimeCheckpointState checkpointState = new RuntimeCheckpointState(
                run.id(),
                run.sessionId(),
                run.tenant(),
                run.principal(),
                completedIteration + 1,
                fingerprints,
                messageCursor,
                summary,
                run.configurationSnapshot(),
                configuration.model().configurationDigest(),
                "priority-retention-v1/session-window-v1",
                "heuristic-chars-v1",
                "deterministic-session-v1",
                toolReferences,
                pendingInteraction,
                forcedContextRebuildAttempts,
                assets,
                memorySelection.memories(),
                memorySelection.retrievalPolicyVersion(),
                memorySelection.queryDigest(),
                modelContinuations,
                skillActivations,
                capabilityReferences,
                capturedAt);
        long assembleMillis = elapsedMillis(phaseStarted);
        phaseStarted = System.nanoTime();
        String stateHash = RuntimeCheckpointStateHasher.digest(checkpointState);
        long hashMillis = elapsedMillis(phaseStarted);
        Checkpoint checkpoint = new Checkpoint(
                new CheckpointId(id),
                run.id(),
                Optional.empty(),
                type,
                CheckpointStatus.VERIFIED,
                sequence,
                new CheckpointPayloadRef("runtime-store", "checkpoint/" + id, "runtime-loop-state", "4.0"),
                stateHash,
                time.now());
        LOGGER.info(
                "event=checkpoint.snapshot runId={} checkpointId={} sequence={} type={} configurationMs={} summaryMs={} toolReferencesMs={} assetsMs={} interactionMs={} memoryMs={} capabilitiesMs={} continuationsMs={} skillsMs={} cursorMs={} assembleMs={} hashMs={} toolCallCount={} assetCount={} continuationCount={} skillCount={} capabilityCount={} totalMs={}",
                run.id().value(),
                id,
                sequence,
                type,
                configurationMillis,
                summaryMillis,
                toolReferencesMillis,
                assetsMillis,
                interactionMillis,
                memoryMillis,
                capabilitiesMillis,
                continuationsMillis,
                skillsMillis,
                cursorMillis,
                assembleMillis,
                hashMillis,
                toolReferences.size(),
                assets.size(),
                modelContinuations.size(),
                skillActivations.size(),
                capabilityReferences.size(),
                elapsedMillis(started));
        return new Snapshot(checkpoint, checkpointState);
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}

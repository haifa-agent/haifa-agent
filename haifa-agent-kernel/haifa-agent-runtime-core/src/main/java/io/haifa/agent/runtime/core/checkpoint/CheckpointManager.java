package io.haifa.agent.runtime.core.checkpoint;

import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.checkpoint.Checkpoint;
import io.haifa.agent.core.checkpoint.CheckpointId;
import io.haifa.agent.core.checkpoint.CheckpointType;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.runtime.core.storage.CheckpointRepository;
import io.haifa.agent.runtime.core.storage.RuntimeEventAppender;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Persists only intentional pause boundaries; it does not snapshot or restore external capabilities. */
public final class CheckpointManager {
    private final CheckpointRepository repository;
    private final CheckpointSnapshotBuilder snapshots;
    private final RuntimeEventAppender events;
    private final TimeProvider time;

    public CheckpointManager(
            CheckpointRepository repository,
            CheckpointSnapshotBuilder snapshots,
            TimeProvider time,
            RuntimeEventAppender events) {
        this.repository = Objects.requireNonNull(repository);
        this.snapshots = Objects.requireNonNull(snapshots);
        this.time = Objects.requireNonNull(time);
        this.events = Objects.requireNonNull(events);
    }

    public Optional<Checkpoint> capture(
            AgentRun run, int completedIteration, int forcedContextRebuildAttempts, CheckpointType type) {
        if (type != CheckpointType.INTERACTION && type != CheckpointType.MANUAL) return Optional.empty();
        long sequence = repository.latest(run.id()).map(Checkpoint::sequence).orElse(0L) + 1;
        var snapshot = snapshots.build(run, completedIteration, forcedContextRebuildAttempts, type, sequence);
        repository.append(snapshot.checkpoint(), snapshot.state());
        events.append(
                run.id(),
                "checkpoint.available",
                Map.of(
                        "reference",
                        snapshot.checkpoint().id().value(),
                        "kind",
                        "checkpoint",
                        "title",
                        "Pause boundary",
                        "status",
                        "VERIFIED"),
                time.now());
        return Optional.of(snapshot.checkpoint());
    }

    /** The source is recorded by the latest intentional pause, never selected by a caller. */
    public Optional<RuntimeCheckpointState> restore(AgentRun run, Optional<CheckpointId> source) {
        if (source.isEmpty()) return Optional.empty();
        Checkpoint latest = repository
                .latest(run.id())
                .filter(value -> value.id().equals(source.orElseThrow()))
                .orElseThrow(() -> new IllegalStateException("attempt pause boundary is not the latest boundary"));
        RuntimeCheckpointState restored = repository
                .state(latest.id().value())
                .orElseThrow(() -> new IllegalStateException("attempt pause state is unavailable"));
        if (!restored.runId().equals(run.id())
                || !latest.stateHash().equals(RuntimeCheckpointStateHasher.digest(restored))) {
            throw new IllegalStateException("attempt pause state does not match the run or its hash");
        }
        return Optional.of(restored);
    }
}

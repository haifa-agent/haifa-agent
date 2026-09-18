package io.haifa.agent.runtime.core.checkpoint;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.checkpoint.Checkpoint;
import io.haifa.agent.core.checkpoint.CheckpointId;
import io.haifa.agent.core.checkpoint.CheckpointStatus;
import io.haifa.agent.core.checkpoint.CheckpointType;
import io.haifa.agent.core.reference.CheckpointPayloadRef;
import io.haifa.agent.core.run.AgentRun;
import java.util.Objects;
import java.util.Optional;

public final class CheckpointSnapshotBuilder {
    public record Snapshot(Checkpoint checkpoint, RuntimeCheckpointState state) {}

    private final IdentifierGenerator ids;
    private final TimeProvider time;

    public CheckpointSnapshotBuilder(IdentifierGenerator ids, TimeProvider time) {
        this.ids = Objects.requireNonNull(ids);
        this.time = Objects.requireNonNull(time);
    }

    public Snapshot build(
            AgentRun run,
            int completedIteration,
            int forcedContextRebuildAttempts,
            CheckpointType type,
            long sequence) {
        String id = ids.nextValue();
        var state = new RuntimeCheckpointState(run.id(), completedIteration + 1, forcedContextRebuildAttempts);
        return new Snapshot(
                new Checkpoint(
                        new CheckpointId(id),
                        run.id(),
                        Optional.empty(),
                        type,
                        CheckpointStatus.VERIFIED,
                        sequence,
                        new CheckpointPayloadRef("runtime-store", "checkpoint/" + id, "runtime-loop-state", "6.0"),
                        RuntimeCheckpointStateHasher.digest(state),
                        time.now()),
                state);
    }
}

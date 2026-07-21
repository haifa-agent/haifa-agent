package io.haifa.agent.runtime.core.checkpoint;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.checkpoint.Checkpoint;
import io.haifa.agent.core.checkpoint.CheckpointId;
import io.haifa.agent.core.checkpoint.CheckpointStatus;
import io.haifa.agent.core.checkpoint.CheckpointType;
import io.haifa.agent.core.reference.CheckpointPayloadRef;
import io.haifa.agent.core.run.AgentRun;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
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
            AgentRun run, int completedIteration, List<String> fingerprints, CheckpointType type, long sequence) {
        String id = ids.nextValue();
        RuntimeCheckpointState state =
                new RuntimeCheckpointState(run.id(), completedIteration + 1, fingerprints, time.now());
        String hash = hash(run.id().value() + "|" + state.nextIteration() + "|" + state.decisionFingerprints());
        Checkpoint checkpoint = new Checkpoint(
                new CheckpointId(id),
                run.id(),
                Optional.empty(),
                type,
                CheckpointStatus.VERIFIED,
                sequence,
                new CheckpointPayloadRef("runtime-store", "checkpoint/" + id, "runtime-loop-state", "1.0"),
                "sha256:" + hash,
                time.now());
        return new Snapshot(checkpoint, state);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

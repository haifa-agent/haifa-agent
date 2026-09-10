package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.checkpoint.Checkpoint;
import io.haifa.agent.core.checkpoint.CheckpointId;
import io.haifa.agent.core.checkpoint.CheckpointStatus;
import io.haifa.agent.core.checkpoint.CheckpointType;
import io.haifa.agent.core.reference.CheckpointPayloadRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.core.checkpoint.RuntimeCheckpointState;
import io.haifa.agent.runtime.core.checkpoint.RuntimeCheckpointStateHasher;
import io.haifa.agent.store.sqlite.codec.PayloadCodecException;
import io.haifa.agent.store.sqlite.codec.PayloadCodecFailure;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteCheckpointRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-07-25T00:00:00Z");

    @Test
    void atomicallyRoundTripsMetadataAndCompleteRuntimeState(@TempDir java.nio.file.Path directory) {
        SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory);
        SqliteAggregateTestData.prepareRun(foundation);
        RuntimeCheckpointState state = state();
        Checkpoint checkpoint = new Checkpoint(
                new CheckpointId("checkpoint-1"),
                state.runId(),
                Optional.empty(),
                CheckpointType.AUTOMATIC,
                CheckpointStatus.VERIFIED,
                1,
                new CheckpointPayloadRef("sqlite", "checkpoint-1", "runtime-state", "1"),
                RuntimeCheckpointStateHasher.digest(state),
                NOW);

        foundation.checkpoints().append(checkpoint, state);

        assertThat(foundation.checkpoints().latest(state.runId())).contains(checkpoint);
        assertThat(foundation.checkpoints().state(checkpoint.id().value())).contains(state);
        assertThat(foundation.checkpoints().checkpointsFor(state.runId())).containsExactly(checkpoint);
    }

    @Test
    void classifiesMetadataAndPayloadCorruptionAndNeverReturnsState(@TempDir java.nio.file.Path directory)
            throws Exception {
        try (SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory)) {
            SqliteAggregateTestData.prepareRun(foundation);
            RuntimeCheckpointState state = state();
            String stateHash = RuntimeCheckpointStateHasher.digest(state);
            Checkpoint checkpoint = new Checkpoint(
                    new CheckpointId("checkpoint-corrupt"),
                    state.runId(),
                    Optional.empty(),
                    CheckpointType.AUTOMATIC,
                    CheckpointStatus.VERIFIED,
                    1,
                    new CheckpointPayloadRef("sqlite", "checkpoint-corrupt", "runtime-state", "1"),
                    stateHash,
                    NOW);
            foundation.checkpoints().append(checkpoint, state);

            update(
                    foundation,
                    "UPDATE checkpoint SET state_hash = ? WHERE checkpoint_id = ?",
                    "sha256:" + "f".repeat(64),
                    checkpoint.id().value());
            assertThatThrownBy(
                            () -> foundation.checkpoints().state(checkpoint.id().value()))
                    .isInstanceOf(SqliteStoreException.class)
                    .extracting(exception -> ((SqliteStoreException) exception).failure())
                    .isEqualTo(SqliteStoreFailure.CHECKPOINT_CORRUPTION);

            update(
                    foundation,
                    "UPDATE checkpoint SET state_hash = ? WHERE checkpoint_id = ?",
                    stateHash,
                    checkpoint.id().value());
            update(
                    foundation,
                    "UPDATE checkpoint_payload SET payload_hash = ? WHERE checkpoint_id = ?",
                    "sha256:" + "e".repeat(64),
                    checkpoint.id().value());
            assertThatThrownBy(
                            () -> foundation.checkpoints().state(checkpoint.id().value()))
                    .isInstanceOf(PayloadCodecException.class)
                    .extracting(exception -> ((PayloadCodecException) exception).failure())
                    .isEqualTo(PayloadCodecFailure.HASH_MISMATCH);
        }
    }

    private static void update(SqliteStoreFoundation foundation, String sql, String value, String checkpointId)
            throws Exception {
        try (var connection = foundation.connections().openConnection();
                var statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            statement.setString(2, checkpointId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private static RuntimeCheckpointState state() {
        return new RuntimeCheckpointState(new AgentRunId("run"), 1, 0);
    }
}

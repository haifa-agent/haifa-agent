package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.checkpoint.Checkpoint;
import io.haifa.agent.core.checkpoint.CheckpointId;
import io.haifa.agent.core.checkpoint.CheckpointStatus;
import io.haifa.agent.core.checkpoint.CheckpointType;
import io.haifa.agent.core.message.MessageCursor;
import io.haifa.agent.core.reference.CheckpointPayloadRef;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.RunConfigurationSnapshotRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.runtime.core.checkpoint.RuntimeCheckpointState;
import io.haifa.agent.runtime.core.checkpoint.RuntimeCheckpointStateHasher;
import java.time.Instant;
import java.util.List;
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

    private static RuntimeCheckpointState state() {
        return new RuntimeCheckpointState(
                new AgentRunId("run"),
                new AgentSessionId("session"),
                new TenantRef("tenant"),
                new PrincipalRef("principal", "user"),
                1,
                List.of("decision"),
                MessageCursor.BEFORE_FIRST,
                Optional.empty(),
                new RunConfigurationSnapshotRef("config", "sha256:config"),
                "sha256:model",
                "context-v1",
                "estimator-v1",
                "compressor-v1",
                List.of(),
                Optional.empty(),
                0,
                List.of(),
                List.of(),
                "memory-v1",
                "sha256:none",
                List.of(),
                List.of(),
                List.of(),
                NOW);
    }
}

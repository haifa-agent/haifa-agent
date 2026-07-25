package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import io.haifa.agent.runtime.api.checkpoint.CapabilityCheckpointCaptureContext;
import io.haifa.agent.runtime.api.checkpoint.CapabilityCheckpointCaptureStatus;
import io.haifa.agent.runtime.api.checkpoint.CapabilityCheckpointParticipant;
import io.haifa.agent.runtime.api.checkpoint.CapabilityCheckpointParticipantId;
import io.haifa.agent.runtime.api.checkpoint.CapabilityCheckpointRef;
import io.haifa.agent.runtime.api.checkpoint.CapabilityCheckpointRestoreContext;
import io.haifa.agent.runtime.api.checkpoint.CapabilityCheckpointValidation;
import io.haifa.agent.runtime.core.bootstrap.EffectiveCapability;
import io.haifa.agent.runtime.core.checkpoint.CapabilityCheckpointRegistry;
import io.haifa.agent.runtime.core.checkpoint.CheckpointRestoreException;
import io.haifa.agent.runtime.core.checkpoint.CheckpointRestoreFailure;
import io.haifa.agent.runtime.core.checkpoint.RuntimeCheckpointState;
import io.haifa.agent.runtime.core.checkpoint.RuntimeCheckpointStateHasher;
import io.haifa.agent.store.sqlite.codec.PayloadCodecException;
import io.haifa.agent.store.sqlite.codec.PayloadCodecFailure;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test
    void reopensPersistentCapabilityReferenceAndFailsClosedWithoutItsProvider(@TempDir java.nio.file.Path directory)
            throws Exception {
        java.nio.file.Path providerState =
                java.nio.file.Files.writeString(directory.resolve("persistent-capability.state"), "stable-state");
        io.haifa.agent.common.io.SecureFilePermissions.secureFile(providerState);
        AtomicInteger restores = new AtomicInteger();
        PersistentCapabilityParticipant firstParticipant = new PersistentCapabilityParticipant(providerState, restores);
        CapabilityCheckpointRef reference = firstParticipant.capture(new CapabilityCheckpointCaptureContext(
                new AgentRunId("run"),
                new AgentSessionId("session"),
                new TenantRef("tenant"),
                new PrincipalRef("principal", "user"),
                Set.of(firstParticipant.capabilityId()),
                "checkpoint-capability",
                NOW));
        RuntimeCheckpointState state = state(List.of(reference));
        Checkpoint checkpoint = new Checkpoint(
                new CheckpointId("checkpoint-capability"),
                state.runId(),
                Optional.empty(),
                CheckpointType.AUTOMATIC,
                CheckpointStatus.VERIFIED,
                1,
                new CheckpointPayloadRef("sqlite", "checkpoint-capability", "runtime-state", "1"),
                RuntimeCheckpointStateHasher.digest(state),
                NOW);
        try (SqliteStoreFoundation first = SqliteTestSupport.foundation(directory)) {
            SqliteAggregateTestData.prepareRun(first);
            first.checkpoints().append(checkpoint, state);
        }

        try (SqliteStoreFoundation reopened = SqliteTestSupport.foundation(directory)) {
            var run = reopened.runs().find(state.runId()).orElseThrow();
            RuntimeCheckpointState restored =
                    reopened.checkpoints().state(checkpoint.id().value()).orElseThrow();
            var capability = List.of(
                    new EffectiveCapability(firstParticipant.capabilityId(), "1", null, "sha256:test-capability"));
            var rebuiltParticipant = new PersistentCapabilityParticipant(providerState, restores);
            new CapabilityCheckpointRegistry(List.of(rebuiltParticipant))
                    .validateAndRestore(run, capability, restored.capabilityCheckpoints(), NOW.plusSeconds(1));
            assertThat(restores).hasValue(1);

            assertThatThrownBy(() -> CapabilityCheckpointRegistry.empty()
                            .validateAndRestore(run, capability, restored.capabilityCheckpoints(), NOW.plusSeconds(2)))
                    .isInstanceOf(CheckpointRestoreException.class)
                    .extracting(exception -> ((CheckpointRestoreException) exception).failure())
                    .isEqualTo(CheckpointRestoreFailure.CAPABILITY_STATE_INVALID);
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
        return state(List.of());
    }

    private static RuntimeCheckpointState state(List<CapabilityCheckpointRef> capabilityCheckpoints) {
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
                capabilityCheckpoints,
                NOW);
    }

    private static final class PersistentCapabilityParticipant implements CapabilityCheckpointParticipant {
        private static final CapabilityCheckpointParticipantId ID =
                new CapabilityCheckpointParticipantId("persistent-test-provider");
        private final java.nio.file.Path state;
        private final AtomicInteger restores;

        private PersistentCapabilityParticipant(java.nio.file.Path state, AtomicInteger restores) {
            this.state = state;
            this.restores = restores;
        }

        @Override
        public CapabilityCheckpointParticipantId id() {
            return ID;
        }

        @Override
        public String version() {
            return "1";
        }

        @Override
        public String capabilityId() {
            return "test.persistent";
        }

        @Override
        public CapabilityCheckpointRef capture(CapabilityCheckpointCaptureContext context) {
            return new CapabilityCheckpointRef(
                    capabilityId(),
                    id(),
                    version(),
                    state.getFileName().toString(),
                    "sha256:stable-state",
                    CapabilityCheckpointCaptureStatus.CAPTURED);
        }

        @Override
        public CapabilityCheckpointValidation validate(
                CapabilityCheckpointRef reference, CapabilityCheckpointRestoreContext context) {
            try {
                return java.nio.file.Files.readString(state).equals("stable-state")
                        ? CapabilityCheckpointValidation.accepted()
                        : CapabilityCheckpointValidation.rejected("DRIFT", "persistent state changed");
            } catch (java.io.IOException exception) {
                return CapabilityCheckpointValidation.rejected("UNAVAILABLE", "persistent state is unavailable");
            }
        }

        @Override
        public void restore(CapabilityCheckpointRef reference, CapabilityCheckpointRestoreContext context) {
            restores.incrementAndGet();
        }
    }
}

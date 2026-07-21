package io.haifa.agent.runtime.core.checkpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.reference.RunConfigurationSnapshotRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunSpec;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.runtime.api.checkpoint.CapabilityCheckpointCaptureContext;
import io.haifa.agent.runtime.api.checkpoint.CapabilityCheckpointCaptureStatus;
import io.haifa.agent.runtime.api.checkpoint.CapabilityCheckpointParticipant;
import io.haifa.agent.runtime.api.checkpoint.CapabilityCheckpointParticipantId;
import io.haifa.agent.runtime.api.checkpoint.CapabilityCheckpointRef;
import io.haifa.agent.runtime.api.checkpoint.CapabilityCheckpointRestoreContext;
import io.haifa.agent.runtime.api.checkpoint.CapabilityCheckpointValidation;
import io.haifa.agent.runtime.core.bootstrap.EffectiveCapability;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CapabilityCheckpointRegistryTest {
    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");

    @Test
    void selectsOnlyFrozenCapabilitiesAndValidatesAllBeforeRestore() {
        var workspace = new Participant("workspace", "project.workspace", true);
        var unused = new Participant("unused", "browser", true);
        var registry = new CapabilityCheckpointRegistry(List.of(unused, workspace));

        assertThat(registry.capture(run(), List.of(), "checkpoint-chat", NOW)).isEmpty();
        var references = registry.capture(
                run(),
                List.of(new EffectiveCapability("project.workspace", "1", "binding", "sha256:config")),
                "checkpoint-1",
                NOW);

        assertThat(references).singleElement().satisfies(reference -> {
            assertThat(reference.participantId().value()).isEqualTo("workspace");
            assertThat(reference.stateDigest()).isEqualTo("sha256:workspace");
        });
        registry.validateAndRestore(
                run(),
                List.of(new EffectiveCapability("project.workspace", "1", "binding", "sha256:config")),
                references,
                NOW);
        assertThat(workspace.restores).hasValue(1);
        assertThat(unused.captures).hasValue(0);
    }

    @Test
    void rejectsDuplicateParticipantsAndFailedValidationWithoutPartialRestore() {
        var first = new Participant("workspace", "project.workspace", true);
        assertThatThrownBy(() -> new CapabilityCheckpointRegistry(List.of(first, first)))
                .isInstanceOf(IllegalArgumentException.class);

        var rejected = new Participant("workspace", "project.workspace", false);
        var registry = new CapabilityCheckpointRegistry(List.of(rejected));
        var references = registry.capture(
                run(),
                List.of(new EffectiveCapability("project.workspace", "1", "binding", "sha256:config")),
                "checkpoint-1",
                NOW);
        assertThatThrownBy(() -> registry.validateAndRestore(
                        run(),
                        List.of(new EffectiveCapability("project.workspace", "1", "binding", "sha256:config")),
                        references,
                        NOW))
                .isInstanceOf(CheckpointRestoreException.class)
                .extracting("failure")
                .isEqualTo(CheckpointRestoreFailure.CAPABILITY_STATE_INVALID);
        assertThat(rejected.restores).hasValue(0);
    }

    private static AgentRun run() {
        var id = new AgentRunId("run-1");
        return AgentRun.createRoot(
                id,
                new AgentRunSpec(
                        new AgentSessionId("session-1"),
                        new ProjectRef("project-1"),
                        new TenantRef("tenant-1"),
                        new PrincipalRef("principal-1", "user"),
                        new AgentDefinitionId("coding"),
                        new AgentDefinitionVersion(1, 0, 0),
                        "coding",
                        "1",
                        AgentRunType.CODING,
                        "test",
                        new AgentRunBudget(100, 100, 100, 10, 10, 2, "USD", 100),
                        new AgentRunLimits(10, 2, 1, 60_000, 10_000),
                        new RunConfigurationSnapshotRef("config", "sha256:config")),
                NOW);
    }

    private static final class Participant implements CapabilityCheckpointParticipant {
        private final CapabilityCheckpointParticipantId id;
        private final String capability;
        private final boolean valid;
        private final AtomicInteger captures = new AtomicInteger();
        private final AtomicInteger restores = new AtomicInteger();

        private Participant(String id, String capability, boolean valid) {
            this.id = new CapabilityCheckpointParticipantId(id);
            this.capability = capability;
            this.valid = valid;
        }

        @Override
        public CapabilityCheckpointParticipantId id() {
            return id;
        }

        @Override
        public String version() {
            return "1";
        }

        @Override
        public String capabilityId() {
            return capability;
        }

        @Override
        public CapabilityCheckpointRef capture(CapabilityCheckpointCaptureContext context) {
            captures.incrementAndGet();
            return new CapabilityCheckpointRef(
                    capability,
                    id,
                    version(),
                    "payload:" + id.value(),
                    "sha256:" + id.value(),
                    CapabilityCheckpointCaptureStatus.CAPTURED);
        }

        @Override
        public CapabilityCheckpointValidation validate(
                CapabilityCheckpointRef reference, CapabilityCheckpointRestoreContext context) {
            return valid
                    ? CapabilityCheckpointValidation.accepted()
                    : CapabilityCheckpointValidation.rejected("DRIFT", "content changed");
        }

        @Override
        public void restore(CapabilityCheckpointRef reference, CapabilityCheckpointRestoreContext context) {
            restores.incrementAndGet();
        }
    }
}

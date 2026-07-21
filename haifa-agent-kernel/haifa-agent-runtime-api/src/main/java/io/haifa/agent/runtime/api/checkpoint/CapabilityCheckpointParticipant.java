package io.haifa.agent.runtime.api.checkpoint;

public interface CapabilityCheckpointParticipant {
    CapabilityCheckpointParticipantId id();

    String version();

    String capabilityId();

    CapabilityCheckpointRef capture(CapabilityCheckpointCaptureContext context);

    CapabilityCheckpointValidation validate(
            CapabilityCheckpointRef reference, CapabilityCheckpointRestoreContext context);

    void restore(CapabilityCheckpointRef reference, CapabilityCheckpointRestoreContext context);
}

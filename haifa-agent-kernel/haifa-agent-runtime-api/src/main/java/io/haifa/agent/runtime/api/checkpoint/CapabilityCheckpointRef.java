package io.haifa.agent.runtime.api.checkpoint;

import java.util.Objects;

public record CapabilityCheckpointRef(
        String capabilityId,
        CapabilityCheckpointParticipantId participantId,
        String participantVersion,
        String payloadRef,
        String stateDigest,
        CapabilityCheckpointCaptureStatus captureStatus) {
    public CapabilityCheckpointRef {
        capabilityId = requireText(capabilityId, "capabilityId");
        participantId = Objects.requireNonNull(participantId, "participantId must not be null");
        participantVersion = requireText(participantVersion, "participantVersion");
        payloadRef = requireText(payloadRef, "payloadRef");
        stateDigest = requireText(stateDigest, "stateDigest");
        captureStatus = Objects.requireNonNull(captureStatus, "captureStatus must not be null");
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}

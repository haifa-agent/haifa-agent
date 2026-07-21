package io.haifa.agent.runtime.api.checkpoint;

import java.util.Objects;

public record CapabilityCheckpointParticipantId(String value) implements Comparable<CapabilityCheckpointParticipantId> {
    public CapabilityCheckpointParticipantId {
        value = requireText(value, "value");
    }

    @Override
    public int compareTo(CapabilityCheckpointParticipantId other) {
        return value.compareTo(other.value);
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}

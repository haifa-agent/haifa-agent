package io.haifa.agent.orchestration.core.spi;

import java.util.Objects;

/** Frozen durable adapter and codec coordinate validated before recovery. */
public record WorkflowPersistenceBinding(
        String adapterCoordinate, String adapterVersion, String adapterConfigurationDigest, int stateCodecVersion) {
    public WorkflowPersistenceBinding {
        adapterCoordinate = required(adapterCoordinate, "adapterCoordinate");
        adapterVersion = required(adapterVersion, "adapterVersion");
        adapterConfigurationDigest = required(adapterConfigurationDigest, "adapterConfigurationDigest");
        if (stateCodecVersion < 1) {
            throw new IllegalArgumentException("stateCodecVersion must be positive");
        }
    }

    private static String required(String value, String name) {
        String normalized =
                Objects.requireNonNull(value, name + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }
}

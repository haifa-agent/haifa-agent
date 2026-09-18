package io.haifa.agent.sdk.product;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Frozen product-level governance for artifact production and delivery. */
public record ProductArtifactPolicy(
        long maxArtifactBytes,
        int maxArtifactsPerRun,
        long maxArtifactBytesPerRun,
        Set<String> allowedMediaTypes,
        boolean rangeSupported,
        long localSoftLimitBytes,
        long localHardLimitBytes,
        boolean requiredCompletionGate) {

    public ProductArtifactPolicy {
        if (maxArtifactBytes < 0 || maxArtifactsPerRun < 0 || maxArtifactBytesPerRun < 0) {
            throw new IllegalArgumentException("artifact limits must not be negative");
        }
        if (localSoftLimitBytes < 0 || localHardLimitBytes < localSoftLimitBytes) {
            throw new IllegalArgumentException("artifact local hard limit must be at least the soft limit");
        }
        allowedMediaTypes = Objects.requireNonNull(allowedMediaTypes, "allowedMediaTypes must not be null").stream()
                .map(value -> ProductValues.text(value, "allowedMediaTypes entry", 128))
                .collect(Collectors.toUnmodifiableSet());
        if (maxArtifactsPerRun == 0) {
            if (maxArtifactBytes != 0
                    || maxArtifactBytesPerRun != 0
                    || !allowedMediaTypes.isEmpty()
                    || rangeSupported
                    || localSoftLimitBytes != 0
                    || localHardLimitBytes != 0
                    || requiredCompletionGate) {
                throw new IllegalArgumentException("disabled artifact policy must use zero limits and no behavior");
            }
        } else {
            if (maxArtifactBytes < 1 || maxArtifactBytesPerRun < maxArtifactBytes) {
                throw new IllegalArgumentException("enabled artifact policy requires coherent byte limits");
            }
            if (allowedMediaTypes.isEmpty()) {
                throw new IllegalArgumentException("enabled artifact policy requires allowed media types");
            }
            if (localHardLimitBytes < maxArtifactBytes) {
                throw new IllegalArgumentException("artifact local hard limit must fit one artifact");
            }
        }
    }

    public static ProductArtifactPolicy disabled() {
        return new ProductArtifactPolicy(0, 0, 0, Set.of(), false, 0, 0, false);
    }
}

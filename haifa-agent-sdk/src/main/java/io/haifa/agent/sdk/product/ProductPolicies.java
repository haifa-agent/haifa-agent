package io.haifa.agent.sdk.product;

import java.util.Objects;

/** Structured product policies frozen into the profile configuration identity. */
public record ProductPolicies(
        ProductMemoryPolicy memory, ProductArtifactPolicy artifact, ProductExecutionPolicy execution) {

    public ProductPolicies {
        memory = Objects.requireNonNull(memory, "memory must not be null");
        artifact = Objects.requireNonNull(artifact, "artifact must not be null");
        execution = Objects.requireNonNull(execution, "execution must not be null");
    }

    public static ProductPolicies safeDefaults() {
        return new ProductPolicies(
                ProductMemoryPolicy.safeDefault(), ProductArtifactPolicy.disabled(), ProductExecutionPolicy.disabled());
    }
}

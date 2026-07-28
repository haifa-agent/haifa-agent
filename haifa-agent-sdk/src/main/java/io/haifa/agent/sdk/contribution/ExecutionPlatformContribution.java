package io.haifa.agent.sdk.contribution;

import io.haifa.agent.sdk.product.ProductCapabilities;
import java.util.Objects;

/** Logical identity of the execution/sandbox adapter selected for a product. */
public final class ExecutionPlatformContribution extends AbstractSdkContribution {
    private final String isolationMode;

    public ExecutionPlatformContribution(SdkContributionMetadata metadata, String isolationMode) {
        super(metadata);
        if (!ProductCapabilities.EXECUTION.equals(metadata.capabilityId())) {
            throw new IllegalArgumentException("execution contribution must provide the execution capability");
        }
        this.isolationMode = Objects.requireNonNull(isolationMode, "isolationMode must not be null")
                .trim();
        if (this.isolationMode.isEmpty() || this.isolationMode.length() > 128) {
            throw new IllegalArgumentException("isolationMode must contain 1 to 128 characters");
        }
    }

    public String isolationMode() {
        return isolationMode;
    }
}

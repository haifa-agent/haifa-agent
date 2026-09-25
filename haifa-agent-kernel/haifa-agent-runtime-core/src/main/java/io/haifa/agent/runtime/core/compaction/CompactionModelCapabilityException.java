package io.haifa.agent.runtime.core.compaction;

import io.haifa.agent.model.api.ModelCapability;
import java.util.Objects;

/**
 * Raised when the session model cannot serve semantic compaction at all, for example because the frozen snapshot
 * does not declare {@link ModelCapability#STRUCTURED_OUTPUT}. The gap is deterministic for the whole session, so
 * retrying the summary model is pointless and compaction degrades to the deterministic compressor instead.
 */
public final class CompactionModelCapabilityException extends RuntimeException {

    private final String modelId;
    private final ModelCapability missingCapability;

    public CompactionModelCapabilityException(String modelId, ModelCapability missingCapability) {
        super("semantic compaction requires model capability " + missingCapability + ", but model '" + modelId
                + "' does not declare it; switch the session to a model that supports it to keep full-fidelity"
                + " compaction");
        this.modelId = Objects.requireNonNull(modelId, "modelId must not be null");
        this.missingCapability = Objects.requireNonNull(missingCapability, "missingCapability must not be null");
    }

    public String modelId() {
        return modelId;
    }

    public ModelCapability missingCapability() {
        return missingCapability;
    }
}

package io.haifa.agent.model.api;

import java.util.Objects;

/** Exact Binding streaming features and its partial-output failure contract. */
public record ModelStreamingProfile(
        boolean nativeStreaming,
        boolean usageStreaming,
        boolean reasoningStreaming,
        ModelPartialOutputFailureBehavior partialOutputFailureBehavior) {

    public ModelStreamingProfile {
        partialOutputFailureBehavior =
                Objects.requireNonNull(partialOutputFailureBehavior, "partialOutputFailureBehavior must not be null");
        if (!nativeStreaming && (usageStreaming || reasoningStreaming)) {
            throw new IllegalArgumentException("usage or reasoning streaming requires native streaming");
        }
    }

    public static ModelStreamingProfile disabled() {
        return new ModelStreamingProfile(false, false, false, ModelPartialOutputFailureBehavior.NON_RETRYABLE);
    }
}

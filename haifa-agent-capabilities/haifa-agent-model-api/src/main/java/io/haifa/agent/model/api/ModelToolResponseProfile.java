package io.haifa.agent.model.api;

/** Exact Binding facts for tool and structured-response execution. */
public record ModelToolResponseProfile(
        boolean toolCallingSupported,
        boolean structuredOutputSupported,
        boolean toolReasoningContinuationRequired) {

    public static ModelToolResponseProfile fromCapabilities(
            java.util.Set<ModelCapability> capabilities, boolean toolReasoningContinuationRequired) {
        java.util.Objects.requireNonNull(capabilities, "capabilities must not be null");
        return new ModelToolResponseProfile(
                capabilities.contains(ModelCapability.TOOL_CALLING),
                capabilities.contains(ModelCapability.STRUCTURED_OUTPUT),
                toolReasoningContinuationRequired);
    }
}

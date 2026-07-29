package io.haifa.agent.runtime.core.recovery;

import io.haifa.agent.core.tool.ToolCallStatus;
import java.util.Objects;

public record ToolOutcomeObservation(
        String toolCallRef,
        ToolCallStatus terminalStatus,
        ToolFailureCategory category,
        FailureFingerprint fingerprint) {
    public ToolOutcomeObservation {
        toolCallRef = FailureFingerprint.digest(
                java.util.List.of(Objects.requireNonNull(toolCallRef, "toolCallRef must not be null")));
        terminalStatus = Objects.requireNonNull(terminalStatus, "terminalStatus must not be null");
        category = Objects.requireNonNull(category, "category must not be null");
        fingerprint = Objects.requireNonNull(fingerprint, "fingerprint must not be null");
    }
}

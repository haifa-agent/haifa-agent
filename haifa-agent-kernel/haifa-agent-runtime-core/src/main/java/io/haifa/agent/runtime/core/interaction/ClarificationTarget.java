package io.haifa.agent.runtime.core.interaction;

import io.haifa.agent.core.tool.ToolCallId;
import java.util.Objects;

/** Interaction target that resumes exactly one persisted Tool Call when the question is answered. */
public record ClarificationTarget(ToolCallId toolCallId) implements InteractionTarget {
    public ClarificationTarget {
        Objects.requireNonNull(toolCallId, "toolCallId must not be null");
    }
}

package io.haifa.agent.runtime.core.interaction;

import io.haifa.agent.core.tool.ToolCallId;
import java.util.Objects;

public record ToolApprovalTarget(
        ToolCallId toolCallId, String coordinate, String definitionHash, String argumentsDigest, String principalScope)
        implements InteractionTarget {
    public ToolApprovalTarget {
        Objects.requireNonNull(toolCallId, "toolCallId");
        coordinate = requireText(coordinate, "coordinate");
        definitionHash = requireText(definitionHash, "definitionHash");
        argumentsDigest = requireText(argumentsDigest, "argumentsDigest");
        principalScope = requireText(principalScope, "principalScope");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}

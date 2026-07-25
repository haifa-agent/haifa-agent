package io.haifa.agent.store.sqlite.payload;

import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.runtime.core.interaction.GenericInteractionTarget;
import io.haifa.agent.runtime.core.interaction.InteractionTarget;
import io.haifa.agent.runtime.core.interaction.ToolApprovalTarget;

public record InteractionTargetPayload(
        String kind,
        String type,
        String toolCallId,
        String coordinate,
        String definitionHash,
        String argumentsDigest,
        String principalScope) {

    public static InteractionTargetPayload from(InteractionTarget target) {
        if (target instanceof GenericInteractionTarget generic) {
            return new InteractionTargetPayload("generic", generic.type(), null, null, null, null, null);
        }
        if (target instanceof ToolApprovalTarget tool) {
            return new InteractionTargetPayload(
                    "tool-approval",
                    null,
                    tool.toolCallId().value(),
                    tool.coordinate(),
                    tool.definitionHash(),
                    tool.argumentsDigest(),
                    tool.principalScope());
        }
        throw new IllegalArgumentException("unsupported interaction target");
    }

    public InteractionTarget toDomain() {
        return switch (kind) {
            case "generic" -> new GenericInteractionTarget(type);
            case "tool-approval" ->
                new ToolApprovalTarget(
                        new ToolCallId(toolCallId), coordinate, definitionHash, argumentsDigest, principalScope);
            default -> throw new IllegalStateException("unknown interaction target kind");
        };
    }
}

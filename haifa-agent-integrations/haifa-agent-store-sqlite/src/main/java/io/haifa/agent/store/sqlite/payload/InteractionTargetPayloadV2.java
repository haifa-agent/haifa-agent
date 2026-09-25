package io.haifa.agent.store.sqlite.payload;

import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.runtime.api.ApprovalPresentation;
import io.haifa.agent.runtime.core.interaction.GenericInteractionTarget;
import io.haifa.agent.runtime.core.interaction.InteractionTarget;
import io.haifa.agent.runtime.core.interaction.ToolApprovalTarget;
import java.util.Optional;

/**
 * Version 2 encoded interaction target payload. It extends version 1 with the optional, display-only
 * approval presentation that products may supply alongside the safe prompt.
 */
public record InteractionTargetPayloadV2(
        String kind,
        String type,
        String toolCallId,
        String coordinate,
        String definitionHash,
        String argumentsDigest,
        String principalScope,
        String requirementDigest,
        ApprovalPresentationPayload presentation) {

    public static InteractionTargetPayloadV2 from(
            InteractionTarget target, Optional<ApprovalPresentation> presentation) {
        ApprovalPresentationPayload encoded =
                presentation.map(ApprovalPresentationPayload::from).orElse(null);
        if (target instanceof GenericInteractionTarget generic) {
            return new InteractionTargetPayloadV2(
                    "generic", generic.type(), null, null, null, null, null, null, encoded);
        }
        if (target instanceof ToolApprovalTarget tool) {
            return new InteractionTargetPayloadV2(
                    "tool-approval",
                    null,
                    tool.toolCallId().value(),
                    tool.coordinate(),
                    tool.definitionHash(),
                    tool.argumentsDigest(),
                    tool.principalScope(),
                    tool.requirementDigest(),
                    encoded);
        }
        throw new IllegalArgumentException("unsupported interaction target");
    }

    public InteractionTarget toDomain() {
        return switch (kind) {
            case "generic" -> new GenericInteractionTarget(type);
            case "tool-approval" ->
                new ToolApprovalTarget(
                        new ToolCallId(toolCallId),
                        coordinate,
                        definitionHash,
                        argumentsDigest,
                        principalScope,
                        requirementDigest);
            default -> throw new IllegalStateException("unknown interaction target kind");
        };
    }

    public Optional<ApprovalPresentation> presentationDomain() {
        return Optional.ofNullable(presentation).map(ApprovalPresentationPayload::toDomain);
    }
}

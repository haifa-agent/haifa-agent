package io.haifa.agent.runtime.core.interaction;

import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionInputContract;
import io.haifa.agent.runtime.api.InteractionKind;
import java.util.List;

/** Single mapping from legacy internal request types to the authoritative public interaction semantics. */
final class InteractionSemantics {
    private InteractionSemantics() {}

    static InteractionKind kind(InteractionRequest request) {
        if (request.approval()) return InteractionKind.APPROVAL;
        return switch (request.type()) {
            case "clarification" -> InteractionKind.CLARIFICATION;
            case "confirmation" -> InteractionKind.CONFIRMATION;
            case "selection" -> InteractionKind.SELECTION;
            case "input-required" -> InteractionKind.INPUT_REQUIRED;
            case "artifact-review" -> InteractionKind.ARTIFACT_REVIEW;
            case "conflict-resolution" -> InteractionKind.CONFLICT_RESOLUTION;
            default -> unknownKind(request.type());
        };
    }

    static List<InteractionAction> allowedActions(InteractionKind kind) {
        if (kind.equals(InteractionKind.APPROVAL)) {
            return List.of(InteractionAction.APPROVE, InteractionAction.REJECT);
        }
        if (kind.equals(InteractionKind.CONFIRMATION)) {
            return List.of(InteractionAction.CONFIRM, InteractionAction.REJECT);
        }
        if (kind.equals(InteractionKind.ARTIFACT_REVIEW)) {
            return List.of(InteractionAction.SUBMIT, InteractionAction.REJECT);
        }
        if (kind.equals(InteractionKind.CLARIFICATION)
                || kind.equals(InteractionKind.SELECTION)
                || kind.equals(InteractionKind.INPUT_REQUIRED)
                || kind.equals(InteractionKind.CONFLICT_RESOLUTION)) {
            return List.of(InteractionAction.SUBMIT, InteractionAction.CANCEL);
        }
        return List.of();
    }

    static InteractionInputContract inputContract(InteractionKind kind) {
        if (kind.equals(InteractionKind.APPROVAL) || !kind.known()) return InteractionInputContract.NONE;
        if (kind.equals(InteractionKind.CONFIRMATION)) return InteractionInputContract.NONE;
        return InteractionInputContract.contentParts(20, 1_048_576);
    }

    private static InteractionKind unknownKind(String type) {
        try {
            return new InteractionKind(type);
        } catch (IllegalArgumentException ignored) {
            return new InteractionKind("unknown");
        }
    }
}

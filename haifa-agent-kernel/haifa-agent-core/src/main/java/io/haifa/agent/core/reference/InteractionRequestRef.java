package io.haifa.agent.core.reference;

import static io.haifa.agent.core.support.DomainValues.requireText;

/** Reference to an interaction or approval workflow owned by another module. */
public record InteractionRequestRef(String interactionRequestId, String interactionType) {
    public InteractionRequestRef {
        interactionRequestId = requireText(interactionRequestId, "interactionRequestId");
        interactionType = requireText(interactionType, "interactionType");
    }
}

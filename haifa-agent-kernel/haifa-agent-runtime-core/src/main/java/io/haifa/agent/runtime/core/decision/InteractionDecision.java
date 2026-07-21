package io.haifa.agent.runtime.core.decision;

import java.util.Objects;

public record InteractionDecision(String interactionType, String prompt, boolean approval) implements AgentDecision {
    public InteractionDecision {
        interactionType = requireText(interactionType, "interactionType");
        prompt = requireText(prompt, "prompt");
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}

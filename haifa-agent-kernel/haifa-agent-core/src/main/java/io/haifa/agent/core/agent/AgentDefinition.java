package io.haifa.agent.core.agent;

import java.util.Objects;

/** Immutable definition used to create Agent sessions and runs. */
public record AgentDefinition(AgentId id, String name, String instructions) {

    public AgentDefinition {
        id = Objects.requireNonNull(id, "id must not be null");
        name = requireText(name, "name");
        instructions = requireText(instructions, "instructions");
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}

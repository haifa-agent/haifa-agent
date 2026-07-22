package io.haifa.agent.runtime.core.interaction;

import java.util.Objects;

public record GenericInteractionTarget(String type) implements InteractionTarget {
    public GenericInteractionTarget {
        type = Objects.requireNonNull(type, "type");
    }
}

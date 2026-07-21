package io.haifa.agent.context.item;

import java.util.Objects;

public record TextContextContent(ContextRole role, String text) implements ContextContent {
    public TextContextContent {
        role = Objects.requireNonNull(role, "role must not be null");
        text = Objects.requireNonNull(text, "text must not be null").trim();
        if (text.isEmpty()) throw new IllegalArgumentException("text must not be blank");
    }
}

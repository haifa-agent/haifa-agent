package io.haifa.agent.memory.api;

public record TextMemoryContent(String text) implements MemoryContent {
    public TextMemoryContent {
        text = MemoryValues.content(text, "text", 4096);
    }

    @Override
    public String boundedText() {
        return text;
    }

    @Override
    public int estimatedTokens() {
        return Math.max(1, (text.length() + 3) / 4);
    }
}

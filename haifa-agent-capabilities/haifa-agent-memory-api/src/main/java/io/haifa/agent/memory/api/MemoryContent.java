package io.haifa.agent.memory.api;

public sealed interface MemoryContent permits TextMemoryContent, StructuredMemoryContent, DerivedTextMemoryContent {
    String boundedText();

    int estimatedTokens();
}

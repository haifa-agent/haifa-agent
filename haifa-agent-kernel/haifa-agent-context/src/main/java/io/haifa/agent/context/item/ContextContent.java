package io.haifa.agent.context.item;

public sealed interface ContextContent
        permits MessageContextContent, TextContextContent, AssetDerivedTextContent, MemoryReferenceContent {}

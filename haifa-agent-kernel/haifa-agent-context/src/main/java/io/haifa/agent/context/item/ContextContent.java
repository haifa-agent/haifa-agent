package io.haifa.agent.context.item;

public sealed interface ContextContent
        permits MessageGroupContextContent, TextContextContent, ConversationSummaryContent, MemoryReferenceContent {}

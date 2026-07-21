package io.haifa.agent.context.item;

public sealed interface ContextContent
        permits MessageContextContent,
                MessageGroupContextContent,
                TextContextContent,
                AssetDerivedTextContent,
                ConversationSummaryContent,
                MemoryReferenceContent {}

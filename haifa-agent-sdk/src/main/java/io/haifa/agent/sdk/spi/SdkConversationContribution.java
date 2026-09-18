package io.haifa.agent.sdk.spi;

import io.haifa.agent.sdk.conversation.ConversationStore;

/** Host-side Conversation Session storage SPI selected independently from Runtime persistence. */
public interface SdkConversationContribution extends AutoCloseable {
    ConversationStore conversationStore();

    @Override
    default void close() {}
}

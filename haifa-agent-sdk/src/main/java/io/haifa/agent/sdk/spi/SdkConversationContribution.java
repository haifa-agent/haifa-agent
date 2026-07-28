package io.haifa.agent.sdk.spi;

import io.haifa.agent.sdk.conversation.ConversationStore;
import io.haifa.agent.sdk.product.ProductContribution;

/** Host-side Conversation Session storage SPI selected independently from Runtime persistence. */
public interface SdkConversationContribution extends ProductContribution {
    ConversationStore conversationStore();
}

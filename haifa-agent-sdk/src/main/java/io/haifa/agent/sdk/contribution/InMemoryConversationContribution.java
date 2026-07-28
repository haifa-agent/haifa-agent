package io.haifa.agent.sdk.contribution;

import io.haifa.agent.sdk.conversation.ConversationStore;
import io.haifa.agent.sdk.conversation.InMemoryConversationStore;
import io.haifa.agent.sdk.product.ProductCapabilities;
import io.haifa.agent.sdk.spi.SdkConversationContribution;
import java.util.Objects;

/** Process-local test/development Conversation Session contribution. */
public final class InMemoryConversationContribution extends AbstractSdkContribution
        implements SdkConversationContribution {
    private final ConversationStore conversations;

    public InMemoryConversationContribution(SdkContributionMetadata metadata) {
        this(metadata, new InMemoryConversationStore());
    }

    public InMemoryConversationContribution(SdkContributionMetadata metadata, ConversationStore conversations) {
        super(metadata);
        if (!ProductCapabilities.CONVERSATION.equals(metadata.capabilityId())) {
            throw new IllegalArgumentException("conversation contribution must provide the conversation capability");
        }
        this.conversations = Objects.requireNonNull(conversations, "conversations must not be null");
    }

    @Override
    public ConversationStore conversationStore() {
        return conversations;
    }
}

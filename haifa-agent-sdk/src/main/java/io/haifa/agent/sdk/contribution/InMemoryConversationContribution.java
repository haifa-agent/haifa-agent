package io.haifa.agent.sdk.contribution;

import io.haifa.agent.sdk.conversation.ConversationStore;
import io.haifa.agent.sdk.conversation.InMemoryConversationStore;
import io.haifa.agent.sdk.spi.SdkConversationContribution;
import java.util.Objects;

/** Process-local test/development Conversation Session contribution. */
public record InMemoryConversationContribution(ConversationStore conversations) implements SdkConversationContribution {

    public InMemoryConversationContribution {
        conversations = Objects.requireNonNull(conversations, "conversations must not be null");
    }

    public InMemoryConversationContribution() {
        this(new InMemoryConversationStore());
    }

    @Override
    public ConversationStore conversationStore() {
        return conversations;
    }
}

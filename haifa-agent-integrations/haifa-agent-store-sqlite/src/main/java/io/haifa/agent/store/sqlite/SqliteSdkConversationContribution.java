package io.haifa.agent.store.sqlite;

import io.haifa.agent.sdk.conversation.ConversationStore;
import io.haifa.agent.sdk.spi.SdkConversationContribution;
import java.util.Objects;

public final class SqliteSdkConversationContribution implements SdkConversationContribution {
    private final ConversationStore conversations;

    public SqliteSdkConversationContribution(SqliteStoreFoundation foundation) {
        this.conversations =
                new SqliteConversationStore(Objects.requireNonNull(foundation, "foundation must not be null")
                        .unitOfWork());
    }

    @Override
    public ConversationStore conversationStore() {
        return conversations;
    }
}

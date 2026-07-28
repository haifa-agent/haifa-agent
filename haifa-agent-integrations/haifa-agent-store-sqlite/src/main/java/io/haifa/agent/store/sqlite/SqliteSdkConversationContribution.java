package io.haifa.agent.store.sqlite;

import io.haifa.agent.sdk.contribution.AbstractSdkContribution;
import io.haifa.agent.sdk.contribution.SdkContributionMetadata;
import io.haifa.agent.sdk.conversation.ConversationStore;
import io.haifa.agent.sdk.product.ProductCapabilities;
import io.haifa.agent.sdk.spi.SdkConversationContribution;
import java.util.Objects;

public final class SqliteSdkConversationContribution extends AbstractSdkContribution
        implements SdkConversationContribution {
    private final ConversationStore conversations;

    public SqliteSdkConversationContribution(SdkContributionMetadata metadata, SqliteStoreFoundation foundation) {
        super(metadata);
        if (!ProductCapabilities.CONVERSATION.equals(metadata.capabilityId())) {
            throw new IllegalArgumentException("SQLite conversation must provide the conversation capability");
        }
        this.conversations =
                new SqliteConversationStore(Objects.requireNonNull(foundation, "foundation must not be null")
                        .unitOfWork());
    }

    @Override
    public ConversationStore conversationStore() {
        return conversations;
    }
}

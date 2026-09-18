package io.haifa.agent.store.sqlite;

import io.haifa.agent.runtime.core.model.continuation.ModelContinuationProtector;
import java.time.Clock;
import java.util.Objects;

/** Opens one SQLite foundation and exposes the two independently selectable SDK components. */
public record SqliteSdkContributions(
        SqliteSdkPersistenceContribution persistence, SqliteSdkConversationContribution conversation) {
    public SqliteSdkContributions {
        persistence = Objects.requireNonNull(persistence, "persistence must not be null");
        conversation = Objects.requireNonNull(conversation, "conversation must not be null");
    }

    public static SqliteSdkContributions initialize(
            SqliteStoreConfiguration configuration, Clock clock, ModelContinuationProtector protector) {
        SqliteStoreFoundation foundation = SqliteStoreFoundation.initialize(configuration, clock);
        try {
            return new SqliteSdkContributions(
                    new SqliteSdkPersistenceContribution(foundation, protector),
                    new SqliteSdkConversationContribution(foundation));
        } catch (RuntimeException | Error exception) {
            foundation.close();
            throw exception;
        }
    }
}

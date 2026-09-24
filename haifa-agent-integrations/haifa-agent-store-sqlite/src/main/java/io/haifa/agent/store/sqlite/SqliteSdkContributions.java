package io.haifa.agent.store.sqlite;

import io.haifa.agent.runtime.core.model.continuation.AesGcmModelContinuationProtector;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationProtector;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.sdk.spi.SdkPersistenceContribution;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Supplier;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

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

    /**
     * Opens a persistent SQLite SDK foundation protected by the supplied AES key.
     *
     * <p>This factory keeps Runtime Core continuation types behind the SQLite integration boundary. The caller
     * remains responsible for resolving and retaining the key across process restarts.
     */
    public static SqliteSdkContributions initializeWithKey(
            SqliteStoreConfiguration configuration, Clock clock, SecretKey continuationKey) {
        SecretKey validatedKey = requireAes256Key(continuationKey);
        return initialize(configuration, clock, new AesGcmModelContinuationProtector(validatedKey, new SecureRandom()));
    }

    /**
     * Returns a non-owning persistence view for an SDK assembly whose lifecycle is shorter than this foundation.
     *
     * <p>Closing the borrowed view does not close SQLite. The owner must eventually close {@link #persistence()}.
     */
    public SdkPersistenceContribution borrowedPersistence() {
        return new BorrowedPersistence(persistence);
    }

    private static SecretKey requireAes256Key(SecretKey key) {
        Objects.requireNonNull(key, "continuationKey must not be null");
        if (!"AES".equalsIgnoreCase(key.getAlgorithm())) {
            throw new IllegalArgumentException("continuationKey must use AES");
        }
        byte[] encoded = key.getEncoded();
        try {
            if (encoded == null || encoded.length != 32) {
                throw new IllegalArgumentException("continuationKey must be an encoded 256-bit AES key");
            }
            return new SecretKeySpec(encoded, "AES");
        } finally {
            if (encoded != null) Arrays.fill(encoded, (byte) 0);
        }
    }

    private record BorrowedPersistence(SdkPersistenceContribution delegate) implements SdkPersistenceContribution {
        private BorrowedPersistence {
            Objects.requireNonNull(delegate, "delegate must not be null");
        }

        @Override
        public RuntimePersistencePorts runtimePersistence() {
            return delegate.runtimePersistence();
        }

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return delegate.inTransaction(work);
        }

        @Override
        public void close() {
            // The SqliteSdkContributions owner retains lifecycle authority.
        }
    }
}

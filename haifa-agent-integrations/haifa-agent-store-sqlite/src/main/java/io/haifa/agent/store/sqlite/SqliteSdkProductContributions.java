package io.haifa.agent.store.sqlite;

import io.haifa.agent.artifact.ArtifactService;
import io.haifa.agent.common.id.UuidV7IdentifierGenerator;
import io.haifa.agent.memory.api.MemoryDerivedDataInvalidator;
import io.haifa.agent.memory.api.MemoryUnitOfWork;
import io.haifa.agent.memory.core.DefaultMemoryPolicy;
import io.haifa.agent.memory.core.DefaultMemoryRetriever;
import io.haifa.agent.memory.core.DefaultMemoryService;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationProtector;
import io.haifa.agent.sdk.contribution.ArtifactPlatformContribution;
import io.haifa.agent.sdk.contribution.MemoryPlatformContribution;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Opens one SQLite foundation and exposes Persistence, Conversation, and production Memory. */
public record SqliteSdkProductContributions(
        SqliteSdkPersistenceContribution persistence,
        SqliteSdkConversationContribution conversation,
        MemoryPlatformContribution memory,
        ArtifactPlatformContribution artifact) {
    public SqliteSdkProductContributions {
        Objects.requireNonNull(persistence);
        Objects.requireNonNull(conversation);
        Objects.requireNonNull(memory);
        Objects.requireNonNull(artifact);
    }

    public static SqliteSdkProductContributions initialize(
            SqliteStoreConfiguration configuration, Clock clock, ModelContinuationProtector protector) {
        SqliteStoreFoundation foundation = SqliteStoreFoundation.initialize(configuration, clock);
        try {
            SqliteMemoryStore store =
                    new SqliteMemoryStore(foundation.unitOfWork(), configuration.maximumPayloadBytes());
            MemoryUnitOfWork memoryUnitOfWork = new MemoryUnitOfWork() {
                @Override
                public <T> T execute(java.util.function.Supplier<T> work) {
                    return foundation.unitOfWork().execute(work);
                }

                @Override
                public void afterCommit(Runnable listener) {
                    foundation.unitOfWork().afterCommit(listener);
                }
            };
            var policy = new DefaultMemoryPolicy(false);
            var service = new DefaultMemoryService(
                    store,
                    store,
                    policy,
                    new SqliteMemoryEvidenceVerifier(foundation.unitOfWork()),
                    List.<MemoryDerivedDataInvalidator>of((memory, reason) -> store.invalidateSelections()),
                    store,
                    () -> UUID.randomUUID().toString(),
                    clock::instant,
                    memoryUnitOfWork);
            var retriever = new DefaultMemoryRetriever(store, policy);
            return new SqliteSdkProductContributions(
                    new SqliteSdkPersistenceContribution(foundation, protector),
                    new SqliteSdkConversationContribution(foundation),
                    new MemoryPlatformContribution(service, retriever),
                    new ArtifactPlatformContribution(new ArtifactService(
                            foundation.artifacts(),
                            foundation.artifactPayloads(),
                            new UuidV7IdentifierGenerator(),
                            clock::instant)));
        } catch (RuntimeException | Error exception) {
            foundation.close();
            throw exception;
        }
    }
}

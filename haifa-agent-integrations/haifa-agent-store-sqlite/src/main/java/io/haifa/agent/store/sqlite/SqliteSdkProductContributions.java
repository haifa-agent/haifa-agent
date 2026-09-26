package io.haifa.agent.store.sqlite;

import io.haifa.agent.artifact.ArtifactService;
import io.haifa.agent.common.id.UuidV7IdentifierGenerator;
import io.haifa.agent.memory.core.DefaultMemoryRetriever;
import io.haifa.agent.memory.core.DefaultMemoryService;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationProtector;
import io.haifa.agent.sdk.contribution.ArtifactPlatformContribution;
import io.haifa.agent.sdk.contribution.MemoryPlatformContribution;
import io.haifa.agent.sdk.product.ProductArtifactPolicy;
import io.haifa.agent.sdk.product.ProductMemoryPolicy;
import java.time.Clock;
import java.util.Objects;

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
            SqliteStoreConfiguration configuration,
            Clock clock,
            ModelContinuationProtector protector,
            ProductMemoryPolicy memoryPolicy,
            ProductArtifactPolicy artifactPolicy) {
        Objects.requireNonNull(memoryPolicy, "memoryPolicy must not be null");
        Objects.requireNonNull(artifactPolicy, "artifactPolicy must not be null");
        SqliteStoreFoundation foundation = SqliteStoreFoundation.initialize(configuration, clock);
        try {

            return new SqliteSdkProductContributions(
                    new SqliteSdkPersistenceContribution(foundation, protector),
                    new SqliteSdkConversationContribution(foundation),
                    memory(foundation, clock, memoryPolicy),
                    new ArtifactPlatformContribution(
                            new ArtifactService(
                                    foundation.artifacts(),
                                    foundation.artifactPayloads(),
                                    new UuidV7IdentifierGenerator(),
                                    clock::instant),
                            artifactPolicy));
        } catch (RuntimeException | Error exception) {
            foundation.close();
            throw exception;
        }
    }

    /** Direct Memory CRUD and recall over the given foundation; the foundation owner keeps its lifecycle. */
    static MemoryPlatformContribution memory(
            SqliteStoreFoundation foundation, Clock clock, ProductMemoryPolicy policy) {
        Objects.requireNonNull(policy, "memoryPolicy must not be null");
        SqliteMemoryStore store = new SqliteMemoryStore(foundation.unitOfWork());
        return new MemoryPlatformContribution(
                new DefaultMemoryService(store, new UuidV7IdentifierGenerator(), clock::instant),
                new DefaultMemoryRetriever(store),
                policy);
    }
}

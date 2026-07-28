package io.haifa.agent.sdk.contribution;

import io.haifa.agent.memory.api.MemoryAuditSink;
import io.haifa.agent.memory.api.MemoryRetriever;
import io.haifa.agent.memory.api.MemoryService;
import io.haifa.agent.sdk.product.ProductCapabilities;
import java.util.Objects;

/** Product-selected Memory API implementation and Runtime retrieval bridge. */
public final class MemoryPlatformContribution extends AbstractSdkContribution {
    private final MemoryService service;
    private final MemoryRetriever retriever;
    private final MemoryAuditSink audit;

    public MemoryPlatformContribution(
            SdkContributionMetadata metadata, MemoryService service, MemoryRetriever retriever, MemoryAuditSink audit) {
        super(metadata);
        if (!ProductCapabilities.MEMORY.equals(metadata.capabilityId())) {
            throw new IllegalArgumentException("memory contribution must provide the memory capability");
        }
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.retriever = Objects.requireNonNull(retriever, "retriever must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
    }

    public MemoryService service() {
        return service;
    }

    public MemoryRetriever retriever() {
        return retriever;
    }

    public MemoryAuditSink audit() {
        return audit;
    }
}

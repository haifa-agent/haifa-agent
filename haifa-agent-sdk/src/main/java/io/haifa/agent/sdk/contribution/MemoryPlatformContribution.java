package io.haifa.agent.sdk.contribution;

import io.haifa.agent.memory.api.MemoryRetriever;
import io.haifa.agent.memory.api.MemoryService;
import io.haifa.agent.sdk.product.ProductMemoryPolicy;
import java.util.Objects;

/** Product-selected Memory API implementation, Retrieval bridge and product governance. */
public record MemoryPlatformContribution(MemoryService service, MemoryRetriever retriever, ProductMemoryPolicy policy) {
    public MemoryPlatformContribution {
        service = Objects.requireNonNull(service, "service must not be null");
        retriever = Objects.requireNonNull(retriever, "retriever must not be null");
        policy = Objects.requireNonNull(policy, "policy must not be null");
    }
}

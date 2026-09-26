package io.haifa.agent.sdk.contribution;

import io.haifa.agent.memory.api.MemoryContextRequest;
import io.haifa.agent.memory.api.MemoryRetriever;
import io.haifa.agent.memory.api.MemoryService;
import io.haifa.agent.sdk.product.ProductMemoryPolicy;
import java.util.Objects;
import java.util.function.Predicate;

/** Product-selected Memory service, recall bridge and product limits. */
public record MemoryPlatformContribution(MemoryService service, MemoryRetriever retriever, ProductMemoryPolicy policy) {
    public MemoryPlatformContribution {
        service = Objects.requireNonNull(service, "service must not be null");
        retriever = Objects.requireNonNull(retriever, "retriever must not be null");
        policy = Objects.requireNonNull(policy, "policy must not be null");
    }

    /** Keeps Memory CRUD but recalls into model context only for requests accepted by {@code enabled}. */
    public MemoryPlatformContribution withRecallWhen(Predicate<MemoryContextRequest> enabled) {
        return new MemoryPlatformContribution(service, retriever.onlyWhen(enabled), policy);
    }

    /** Keeps Memory CRUD and never recalls memories into model context. */
    public MemoryPlatformContribution withoutRecall() {
        return new MemoryPlatformContribution(service, MemoryRetriever.none(), policy);
    }
}

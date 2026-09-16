package io.haifa.agent.sdk.contribution;

import io.haifa.agent.memory.api.MemoryRetriever;
import io.haifa.agent.memory.api.MemoryService;
import java.util.Objects;

/** Product-selected Memory API implementation and Runtime retrieval bridge. */
public record MemoryPlatformContribution(MemoryService service, MemoryRetriever retriever) {
    public MemoryPlatformContribution {
        service = Objects.requireNonNull(service, "service must not be null");
        retriever = Objects.requireNonNull(retriever, "retriever must not be null");
    }
}

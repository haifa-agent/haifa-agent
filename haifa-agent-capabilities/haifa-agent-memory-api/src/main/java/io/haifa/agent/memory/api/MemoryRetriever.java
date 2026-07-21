package io.haifa.agent.memory.api;

import java.time.Instant;
import java.util.Optional;

public interface MemoryRetriever {
    MemoryRetrieval retrieve(MemoryQuery query);

    Optional<Memory> findAuthorized(
            MemoryId id,
            MemoryVersion version,
            io.haifa.agent.core.reference.TenantRef tenant,
            io.haifa.agent.core.reference.PrincipalRef owner,
            Instant now);
}

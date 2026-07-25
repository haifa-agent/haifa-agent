package io.haifa.agent.core.session;

import static io.haifa.agent.core.support.DomainValues.immutableMap;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.reference.TenantRef;
import java.time.Instant;
import java.util.Map;

/** Immutable, versioned persistence contract for controlled {@link AgentSession} reconstitution. */
public record AgentSessionPersistenceSnapshot(
        String schemaVersion,
        AgentSessionId id,
        TenantRef tenant,
        PrincipalRef owner,
        ProjectRef project,
        String scope,
        Instant createdAt,
        String status,
        Instant updatedAt,
        Instant closedAt,
        long version,
        Map<String, Object> metadata) {

    public AgentSessionPersistenceSnapshot {
        metadata = immutableMap(metadata, "metadata");
    }
}

package io.haifa.agent.memory.api;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import java.util.List;
import java.util.Objects;

/**
 * Trusted Runtime identity and bounded query input for model-context Memory retrieval. The Run and Agent ids
 * also let a product disable recall for one Run or Agent.
 */
public record MemoryContextRequest(
        TenantRef tenant,
        PrincipalRef owner,
        String runId,
        String sessionId,
        String agentId,
        String queryText,
        int tokenBudget) {
    public MemoryContextRequest {
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        owner = Objects.requireNonNull(owner, "owner must not be null");
        runId = MemoryValues.text(runId, "runId", 256);
        sessionId = MemoryValues.text(sessionId, "sessionId", 256);
        agentId = MemoryValues.text(agentId, "agentId", 256);
        queryText =
                Objects.requireNonNull(queryText, "queryText must not be null").trim();
        if (tokenBudget < 1) throw new IllegalArgumentException("tokenBudget must be positive");
    }

    /** The USER, AGENT and SESSION buckets this request may read, all bound to the trusted tenant and owner. */
    public List<MemoryScope> scopes() {
        return List.of(
                MemoryScope.user(tenant, owner),
                MemoryScope.agent(tenant, owner, agentId),
                MemoryScope.session(tenant, owner, sessionId));
    }
}

package io.haifa.agent.memory.api;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import java.time.Instant;
import java.util.Objects;

/** Trusted Runtime identity and bounded query input for model-context Memory retrieval. */
public record MemoryContextRequest(
        TenantRef tenant,
        PrincipalRef owner,
        String runId,
        String sessionId,
        String queryText,
        int tokenBudget,
        Instant now) {
    public MemoryContextRequest {
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        owner = Objects.requireNonNull(owner, "owner must not be null");
        runId = MemoryValues.text(runId, "runId", 256);
        sessionId = MemoryValues.text(sessionId, "sessionId", 256);
        queryText =
                Objects.requireNonNull(queryText, "queryText must not be null").trim();
        if (tokenBudget < 1) throw new IllegalArgumentException("tokenBudget must be positive");
        now = Objects.requireNonNull(now, "now must not be null");
    }
}

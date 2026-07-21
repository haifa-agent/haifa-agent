package io.haifa.agent.memory.api;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record MemoryQuery(
        TenantRef tenant,
        PrincipalRef owner,
        List<MemoryScope> scopes,
        String queryText,
        Set<MemoryKind> kinds,
        Set<MemorySecurityLabel> allowedSecurityLabels,
        int maxResults,
        int tokenBudget,
        Instant now) {
    public MemoryQuery {
        tenant = Objects.requireNonNull(tenant);
        owner = Objects.requireNonNull(owner);
        scopes = List.copyOf(Objects.requireNonNull(scopes));
        if (scopes.isEmpty()) throw new IllegalArgumentException("scopes must not be empty");
        queryText = Objects.requireNonNull(queryText).trim();
        kinds = Set.copyOf(Objects.requireNonNull(kinds));
        allowedSecurityLabels = Set.copyOf(Objects.requireNonNull(allowedSecurityLabels));
        if (maxResults < 1 || tokenBudget < 1) throw new IllegalArgumentException("query limits must be positive");
        now = Objects.requireNonNull(now);
    }
}

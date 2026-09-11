package io.haifa.agent.memory.api;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface MemoryRetriever {
    MemoryRetrieval retrieve(MemoryQuery query);

    default MemoryContext contextFor(MemoryContextRequest request) {
        var scopes = List.of(
                new MemoryScope(
                        request.tenant(),
                        request.owner(),
                        MemoryScopeType.RUN,
                        request.runId(),
                        MemoryVisibility.OWNER_ONLY,
                        Set.of()),
                new MemoryScope(
                        request.tenant(),
                        request.owner(),
                        MemoryScopeType.SESSION,
                        request.sessionId(),
                        MemoryVisibility.OWNER_ONLY,
                        Set.of()),
                new MemoryScope(
                        request.tenant(),
                        request.owner(),
                        MemoryScopeType.USER,
                        request.owner().principalId(),
                        MemoryVisibility.OWNER_ONLY,
                        Set.of()));
        MemoryRetrieval retrieval = retrieve(new MemoryQuery(
                request.tenant(),
                request.owner(),
                scopes,
                request.queryText(),
                EnumSet.allOf(MemoryKind.class),
                Set.of(MemorySecurityLabel.INTERNAL, MemorySecurityLabel.CONFIDENTIAL),
                8,
                request.tokenBudget(),
                request.now()));
        return new MemoryContext(
                retrieval.results().stream()
                        .map(result -> {
                            Memory memory = result.memory();
                            return new MemorySnippet(
                                    memory.id(),
                                    memory.version(),
                                    memory.scope(),
                                    memory.content().orElseThrow().boundedText(),
                                    result.estimatedTokens(),
                                    memory.normalizedDigest());
                        })
                        .toList(),
                retrieval.policyVersion(),
                retrieval.queryDigest());
    }

    Optional<Memory> findAuthorized(
            MemoryId id,
            MemoryVersion version,
            io.haifa.agent.core.reference.TenantRef tenant,
            io.haifa.agent.core.reference.PrincipalRef owner,
            Instant now);
}

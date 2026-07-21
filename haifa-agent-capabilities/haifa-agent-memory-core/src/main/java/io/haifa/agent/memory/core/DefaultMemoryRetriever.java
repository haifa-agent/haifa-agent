package io.haifa.agent.memory.core;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.memory.api.Memory;
import io.haifa.agent.memory.api.MemoryId;
import io.haifa.agent.memory.api.MemoryPolicy;
import io.haifa.agent.memory.api.MemoryQuery;
import io.haifa.agent.memory.api.MemoryRepository;
import io.haifa.agent.memory.api.MemoryRetrieval;
import io.haifa.agent.memory.api.MemoryRetriever;
import io.haifa.agent.memory.api.MemorySearchResult;
import io.haifa.agent.memory.api.MemoryStatus;
import io.haifa.agent.memory.api.MemoryVersion;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Authorization-first deterministic keyword and recency retrieval without embeddings. */
public final class DefaultMemoryRetriever implements MemoryRetriever {
    private final MemoryRepository repository;
    private final MemoryPolicy policy;

    public DefaultMemoryRetriever(MemoryRepository repository, MemoryPolicy policy) {
        this.repository = java.util.Objects.requireNonNull(repository);
        this.policy = java.util.Objects.requireNonNull(policy);
    }

    @Override
    public MemoryRetrieval retrieve(MemoryQuery query) {
        Set<String> terms = terms(query.queryText());
        var ranked = repository.allMemories().stream()
                .filter(memory -> memory.status() == MemoryStatus.ACTIVE)
                .filter(memory -> !memory.expiredAt(query.now()))
                .filter(memory -> policy.canRead(query, memory))
                .filter(memory -> query.kinds().isEmpty() || query.kinds().contains(memory.kind()))
                .map(memory -> result(memory, terms))
                .filter(result -> terms.isEmpty() || result.relevanceScore() > 0)
                .sorted(Comparator.comparingInt(MemorySearchResult::relevanceScore)
                        .reversed()
                        .thenComparing(result -> result.memory().updatedAt(), Comparator.reverseOrder())
                        .thenComparing(result -> result.memory().id().value())
                        .thenComparingLong(result -> result.memory().version().value()))
                .toList();
        int remaining = query.tokenBudget();
        ArrayList<MemorySearchResult> selected = new ArrayList<>();
        for (MemorySearchResult result : ranked) {
            if (selected.size() >= query.maxResults()) break;
            if (result.estimatedTokens() > remaining) continue;
            selected.add(result);
            remaining -= result.estimatedTokens();
        }
        return new MemoryRetrieval(selected, policy.version(), queryDigest(query));
    }

    @Override
    public Optional<Memory> findAuthorized(
            MemoryId id, MemoryVersion version, TenantRef tenant, PrincipalRef owner, Instant now) {
        return repository
                .find(id, version)
                .filter(memory -> memory.status() == MemoryStatus.ACTIVE)
                .filter(memory -> !memory.expiredAt(now))
                .filter(memory -> memory.scope().tenant().equals(tenant)
                        && memory.scope().owner().equals(owner));
    }

    private MemorySearchResult result(Memory memory, Set<String> terms) {
        String searchable =
                (memory.subjectKey() + " " + memory.content().orElseThrow().boundedText()).toLowerCase(Locale.ROOT);
        int matches =
                Math.toIntExact(terms.stream().filter(searchable::contains).count());
        int score = matches * 100 + (terms.isEmpty() ? 1 : 0);
        int tokens = memory.content().orElseThrow().estimatedTokens();
        return new MemorySearchResult(memory, score, tokens, matches > 0 ? "keyword-match" : "scope-recency");
    }

    private Set<String> terms(String query) {
        return java.util.Arrays.stream(query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_-]+"))
                .filter(value -> value.length() > 1)
                .collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    private String queryDigest(MemoryQuery query) {
        String canonical = query.tenant().tenantId() + "|" + query.owner().principalId() + "|"
                + query.scopes().stream()
                        .map(scope -> scope.type() + ":" + scope.targetId())
                        .sorted()
                        .toList()
                + "|" + terms(query.queryText()) + "|"
                + query.kinds().stream().sorted().toList() + "|"
                + query.maxResults() + "|" + query.tokenBudget();
        try {
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}

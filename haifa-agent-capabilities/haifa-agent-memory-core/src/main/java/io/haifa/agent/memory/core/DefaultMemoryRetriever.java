package io.haifa.agent.memory.core;

import io.haifa.agent.memory.api.Memory;
import io.haifa.agent.memory.api.MemoryContext;
import io.haifa.agent.memory.api.MemoryContextRequest;
import io.haifa.agent.memory.api.MemoryRepository;
import io.haifa.agent.memory.api.MemoryRetriever;
import io.haifa.agent.memory.api.MemorySnippet;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Authorization-first deterministic keyword and recency retrieval without embeddings. Only the trusted
 * tenant/owner USER, AGENT and SESSION buckets of the request are read, and the result is bounded by item count
 * and token budget.
 */
public final class DefaultMemoryRetriever implements MemoryRetriever {
    public static final String POLICY_VERSION = "memory-retrieval-v3";
    public static final int MAX_ITEMS = 16;
    static final int FETCH_LIMIT = 256;

    private final MemoryRepository repository;

    public DefaultMemoryRetriever(MemoryRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    @Override
    public MemoryContext contextFor(MemoryContextRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        Set<String> terms = terms(request.queryText());
        List<Scored> ranked = repository.recent(request.scopes(), FETCH_LIMIT).stream()
                .filter(memory -> request.scopes().contains(memory.scope()))
                .map(memory -> new Scored(memory, score(memory, terms)))
                .sorted(Comparator.comparingInt(Scored::score)
                        .reversed()
                        .thenComparing(scored -> scored.memory().updatedAt(), Comparator.reverseOrder())
                        .thenComparing(scored -> scored.memory().id().value()))
                .toList();
        int remaining = request.tokenBudget();
        List<Memory> chosen = new ArrayList<>();
        for (Scored scored : ranked) {
            if (chosen.size() >= MAX_ITEMS) break;
            Memory memory = scored.memory();
            int tokens = memory.estimatedTokens();
            if (tokens > remaining) continue;
            chosen.add(memory);
            remaining -= tokens;
        }
        List<MemorySnippet> snippets = chosen.stream()
                .sorted(Comparator.comparing(Memory::createdAt)
                        .thenComparing(memory -> memory.id().value()))
                .map(memory -> new MemorySnippet(
                        memory.id(),
                        memory.revision(),
                        memory.scope(),
                        memory.content(),
                        memory.estimatedTokens(),
                        digest(memory.content())))
                .toList();
        return new MemoryContext(snippets, POLICY_VERSION, queryDigest(request, terms));
    }

    private static int score(Memory memory, Set<String> terms) {
        String searchable = (memory.subjectKey() + " " + memory.content()).toLowerCase(Locale.ROOT);
        int matches =
                Math.toIntExact(terms.stream().filter(searchable::contains).count());
        return matches * 100 + (terms.isEmpty() ? 1 : 0);
    }

    private static Set<String> terms(String query) {
        return Arrays.stream(query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_-]+"))
                .filter(value -> value.length() > 1)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static String queryDigest(MemoryContextRequest request, Set<String> terms) {
        return digest(request.tenant().tenantId() + "|" + request.owner().principalId() + "|"
                + request.scopes().stream()
                        .map(scope -> scope.type() + ":" + scope.targetId())
                        .toList()
                + "|" + terms + "|" + request.tokenBudget());
    }

    private static String digest(String value) {
        try {
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private record Scored(Memory memory, int score) {}
}

package io.haifa.agent.memory.api;

import java.util.List;
import java.util.Objects;

/** A single authorization-first, token-bounded Memory context selection. */
public record MemoryContext(List<MemorySnippet> snippets, String policyVersion, String queryDigest) {
    public MemoryContext {
        snippets = List.copyOf(Objects.requireNonNull(snippets, "snippets must not be null"));
        policyVersion = MemoryValues.text(policyVersion, "policyVersion", 128);
        queryDigest = MemoryValues.text(queryDigest, "queryDigest", 128);
    }
}

package io.haifa.agent.memory.api;

import java.util.List;
import java.util.Objects;

public record MemoryRetrieval(List<MemorySearchResult> results, String policyVersion, String queryDigest) {
    public MemoryRetrieval {
        results = List.copyOf(Objects.requireNonNull(results));
        policyVersion = MemoryValues.text(policyVersion, "policyVersion", 128);
        queryDigest = MemoryValues.text(queryDigest, "queryDigest", 128);
    }
}

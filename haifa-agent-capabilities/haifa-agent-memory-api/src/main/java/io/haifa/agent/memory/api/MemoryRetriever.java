package io.haifa.agent.memory.api;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** Selects bounded, authorized Memory snippets for one model turn. */
@FunctionalInterface
public interface MemoryRetriever {
    String DISABLED_POLICY_VERSION = "memory-recall-disabled";

    MemoryContext contextFor(MemoryContextRequest request);

    /** Recall that never returns memories. */
    static MemoryRetriever none() {
        return request -> new MemoryContext(List.of(), DISABLED_POLICY_VERSION, "none");
    }

    /** Recalls only for requests accepted by {@code enabled}, for example to switch recall off per Run or Agent. */
    default MemoryRetriever onlyWhen(Predicate<MemoryContextRequest> enabled) {
        Objects.requireNonNull(enabled, "enabled must not be null");
        MemoryRetriever delegate = this;
        MemoryRetriever disabled = none();
        return request -> enabled.test(request) ? delegate.contextFor(request) : disabled.contextFor(request);
    }
}

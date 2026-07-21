package io.haifa.agent.memory.core;

import io.haifa.agent.memory.api.MemoryEvidenceRef;
import io.haifa.agent.memory.api.MemoryEvidenceVerifier;
import io.haifa.agent.memory.api.MemoryScope;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Test/local evidence registry. Production adapters must resolve these references against authoritative stores. */
public final class InMemoryMemoryEvidenceVerifier implements MemoryEvidenceVerifier {
    private record Key(MemoryScope scope, MemoryEvidenceRef evidence) {}

    private final Set<Key> verified = new HashSet<>();

    public synchronized void register(MemoryScope scope, MemoryEvidenceRef evidence) {
        verified.add(new Key(Objects.requireNonNull(scope), Objects.requireNonNull(evidence)));
    }

    @Override
    public synchronized boolean verify(MemoryScope scope, MemoryEvidenceRef evidence) {
        return verified.contains(new Key(scope, evidence));
    }
}

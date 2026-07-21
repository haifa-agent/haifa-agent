package io.haifa.agent.memory.api;

public interface MemoryEvidenceVerifier {
    boolean verify(MemoryScope scope, MemoryEvidenceRef evidence);
}

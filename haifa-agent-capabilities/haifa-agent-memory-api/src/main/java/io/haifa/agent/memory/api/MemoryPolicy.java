package io.haifa.agent.memory.api;

public interface MemoryPolicy {
    String version();

    MemoryPolicyDecision evaluate(MemoryCandidateDraft draft);

    boolean canPropose(MemoryActor actor, MemoryScope scope);

    boolean canReview(MemoryActor actor, MemoryScope scope);

    boolean canPurge(MemoryActor actor, MemoryScope scope);

    boolean canRead(MemoryQuery query, Memory memory);
}

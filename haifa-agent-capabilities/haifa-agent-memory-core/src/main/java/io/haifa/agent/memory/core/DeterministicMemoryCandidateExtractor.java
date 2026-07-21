package io.haifa.agent.memory.core;

import io.haifa.agent.memory.api.MemoryCandidateDraft;

/** Mechanical extraction boundary: no model inference and no Summary-only evidence are accepted. */
public final class DeterministicMemoryCandidateExtractor {
    public String version() {
        return "deterministic-memory-candidate-v1";
    }

    public MemoryCandidateDraft extract(MemoryObservation observation) {
        boolean evidenceMatches = !observation.sources().isEmpty()
                && !observation.evidence().isEmpty()
                && observation.evidence().stream()
                        .allMatch(evidence -> observation.sources().contains(evidence.source()));
        if (!evidenceMatches) throw new IllegalArgumentException("observation evidence must reference source facts");
        return new MemoryCandidateDraft(
                observation.requestKey(),
                observation.scope(),
                observation.kind(),
                observation.subjectKey(),
                observation.content(),
                observation.sources(),
                observation.evidence(),
                observation.retention(),
                observation.automaticApprovalRequested());
    }
}

package io.haifa.agent.project.reconciliation;

import io.haifa.agent.project.changeset.FileChangeSet;

@FunctionalInterface
public interface MutationOutcomeProbe {
    MutationProbeResult probe(FileChangeSet changeSet);
}

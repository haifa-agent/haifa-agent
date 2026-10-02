package io.haifa.agent.runtime.api;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.reference.RunConfigurationSnapshotRef;
import io.haifa.agent.core.run.AgentRunId;
import java.util.Objects;

/** A body-free projection of the Agent instruction frozen when one Run was admitted. */
public record FrozenInstructionDiagnostic(
        AgentRunId runId,
        AgentDefinitionId definitionId,
        AgentDefinitionVersion definitionVersion,
        RunConfigurationSnapshotRef configurationSnapshot,
        String instructionContentHash) {
    public FrozenInstructionDiagnostic {
        runId = Objects.requireNonNull(runId, "runId must not be null");
        definitionId = Objects.requireNonNull(definitionId, "definitionId must not be null");
        definitionVersion = Objects.requireNonNull(definitionVersion, "definitionVersion must not be null");
        configurationSnapshot = Objects.requireNonNull(configurationSnapshot, "configurationSnapshot must not be null");
        if (instructionContentHash == null || !instructionContentHash.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("instructionContentHash must be a SHA-256 digest");
        }
    }
}

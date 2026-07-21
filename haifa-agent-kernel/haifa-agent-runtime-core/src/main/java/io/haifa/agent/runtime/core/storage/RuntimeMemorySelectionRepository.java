package io.haifa.agent.runtime.core.storage;

import io.haifa.agent.core.run.AgentRunId;
import java.util.Optional;

public interface RuntimeMemorySelectionRepository {
    void saveMemorySelection(AgentRunId runId, RuntimeMemorySelection selection);

    Optional<RuntimeMemorySelection> memorySelection(AgentRunId runId);
}

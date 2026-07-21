package io.haifa.agent.runtime.core.storage;

import io.haifa.agent.core.run.AgentRunId;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface RuntimeEventAppender {
    RuntimeEvent append(AgentRunId runId, String type, Map<String, Object> data, Instant occurredAt);

    List<RuntimeEvent> eventsFor(AgentRunId runId);
}

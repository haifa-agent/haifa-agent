package io.haifa.agent.transport.http;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.RunEventCursor;

/** Adapter port for Task 02's signed opaque cursor codec. */
public interface RunEventCursorTokenCodec {
    String encode(RunEventCursor cursor);

    RunEventCursor decode(AgentRunId expectedRunId, String token);
}

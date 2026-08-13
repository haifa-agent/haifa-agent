package io.haifa.agent.sdk.api;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import java.util.Objects;

/** Minimal blocking facade for one process-local convenience chat call. */
public final class AgentChatHandle {
    private final AgentSessionId sessionId;
    private final AgentRunId runId;
    private final AgentRuns runs;

    AgentChatHandle(AgentSessionId sessionId, AgentRunId runId, AgentRuns runs) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        this.runId = Objects.requireNonNull(runId, "runId must not be null");
        this.runs = Objects.requireNonNull(runs, "runs must not be null");
    }

    public AgentSessionId sessionId() {
        return sessionId;
    }

    public AgentRunId runId() {
        return runId;
    }

    /** Reuses the authoritative Runtime handle completion semantics. */
    public AgentChatResponse await() throws InterruptedException {
        return AgentChatResponse.from(sessionId, runs.await(runId));
    }
}

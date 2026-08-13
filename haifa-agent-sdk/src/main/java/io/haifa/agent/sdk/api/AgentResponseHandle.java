package io.haifa.agent.sdk.api;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import java.util.Objects;

/** Awaitable typed terminal response for one convenience chat call. */
public final class AgentResponseHandle<T extends Record> {
    private final AgentSessionId sessionId;
    private final AgentRunId runId;
    private final AgentRuns runs;
    private final Class<T> responseType;

    AgentResponseHandle(AgentSessionId sessionId, AgentRunId runId, AgentRuns runs, Class<T> responseType) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        this.runId = Objects.requireNonNull(runId, "runId must not be null");
        this.runs = Objects.requireNonNull(runs, "runs must not be null");
        this.responseType = Objects.requireNonNull(responseType, "responseType must not be null");
    }

    public AgentSessionId sessionId() {
        return sessionId;
    }

    public AgentRunId runId() {
        return runId;
    }

    /** Returns a decoded value only after the authoritative Runtime reaches a terminal state. */
    public AgentResponse<T> await() throws InterruptedException {
        return AgentResponse.from(sessionId, runs.await(runId), responseType);
    }
}

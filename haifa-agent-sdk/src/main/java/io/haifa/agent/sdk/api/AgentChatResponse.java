package io.haifa.agent.sdk.api;

import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import java.util.Objects;
import java.util.Optional;

/** Terminal response for the lightweight Chat facade. */
public final class AgentChatResponse {
    private final AgentSessionId sessionId;
    private final AgentRunId runId;
    private final AgentRunStatus status;
    private final Optional<String> output;
    private final Optional<AgentError> error;

    private AgentChatResponse(
            AgentSessionId sessionId,
            AgentRunId runId,
            AgentRunStatus status,
            Optional<String> output,
            Optional<AgentError> error) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        this.runId = Objects.requireNonNull(runId, "runId must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.output = Objects.requireNonNull(output, "output must not be null");
        this.error = Objects.requireNonNull(error, "error must not be null");
        if (!status.isTerminal()) throw new IllegalArgumentException("chat response must be terminal");
    }

    static AgentChatResponse from(AgentSessionId sessionId, AgentRunSnapshot snapshot) {
        return new AgentChatResponse(
                sessionId, snapshot.runId(), snapshot.status(), snapshot.output(), snapshot.error());
    }

    public AgentSessionId sessionId() {
        return sessionId;
    }

    public AgentRunId runId() {
        return runId;
    }

    public AgentRunStatus status() {
        return status;
    }

    public Optional<String> output() {
        return output;
    }

    /** Returns terminal text and fails explicitly when the Run did not produce text. */
    public String text() {
        return output.orElseThrow(() -> new HaifaAgentException(
                "CHAT_OUTPUT_UNAVAILABLE", "chat.text", runId.value(), "CHAT_OUTPUT_UNAVAILABLE"));
    }

    public Optional<AgentError> error() {
        return error;
    }
}

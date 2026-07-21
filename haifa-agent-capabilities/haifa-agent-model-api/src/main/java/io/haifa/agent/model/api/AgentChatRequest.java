package io.haifa.agent.model.api;

import io.haifa.agent.core.run.AgentRunId;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Complete provider-neutral input for one physical chat invocation. */
public record AgentChatRequest(
        ModelCallId callId,
        AgentRunId runId,
        int iteration,
        int attempt,
        ResolvedModelSnapshot model,
        List<ModelMessage> messages,
        List<ModelToolSpecification> tools,
        int maxOutputTokens,
        Duration timeout,
        Map<String, Object> options) {
    public AgentChatRequest {
        callId = Objects.requireNonNull(callId, "callId must not be null");
        runId = Objects.requireNonNull(runId, "runId must not be null");
        if (iteration < 1 || attempt < 1) throw new IllegalArgumentException("iteration and attempt must be positive");
        model = Objects.requireNonNull(model, "model must not be null");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages must not be null"));
        if (messages.isEmpty()) throw new IllegalArgumentException("messages must not be empty");
        tools = List.copyOf(Objects.requireNonNull(tools, "tools must not be null"));
        if (maxOutputTokens < 1) throw new IllegalArgumentException("maxOutputTokens must be positive");
        timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
        options = ModelValues.map(options, "options");
    }
}

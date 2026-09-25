package io.haifa.agent.execution.api;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolCallId;
import java.util.Objects;

/**
 * Transient, best-effort output batch for a running tool invocation.
 *
 * <p>The batch is not a result, is not replayable, and is deliberately bounded. {@code
 * outputTruncated} describes the execution output source; {@code previewDropped} describes loss in
 * this best-effort preview path. Consumers must treat the completed tool result as authoritative.
 */
public record ToolOutputPreview(
        AgentRunId runId,
        ToolCallId toolCallId,
        ExecutionOutputChannel channel,
        String text,
        boolean outputTruncated,
        boolean previewDropped) {
    public ToolOutputPreview {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(toolCallId, "toolCallId must not be null");
        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(text, "text must not be null");
        if (text.length() > 16_384) throw new IllegalArgumentException("preview text is too long");
    }
}

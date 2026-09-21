package io.haifa.agent.execution.api;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolCallId;
import java.util.Objects;
import java.util.function.Consumer;

/** Process-local, transient publisher for best-effort tool output previews. */
public interface ToolOutputPreviewPublisher {
    ToolOutputPreviewSubscription subscribe(AgentRunId runId, Consumer<ToolOutputPreview> consumer);

    ToolOutputPreviewSink open(AgentRunId runId, ToolCallId toolCallId);

    static ToolOutputPreviewPublisher noop() {
        return Noop.INSTANCE;
    }

    static ToolOutputPreviewSink noopSink() {
        return Noop.SINK;
    }

    interface ToolOutputPreviewSink extends AutoCloseable {
        void onOutput(ProcessOutputChunk chunk);

        @Override
        void close();
    }

    interface ToolOutputPreviewSubscription extends AutoCloseable {
        @Override
        void close();
    }

    final class Noop implements ToolOutputPreviewPublisher {
        private static final Noop INSTANCE = new Noop();
        private static final ToolOutputPreviewSubscription SUBSCRIPTION = () -> {};
        private static final ToolOutputPreviewSink SINK = new ToolOutputPreviewSink() {
            @Override
            public void onOutput(ProcessOutputChunk chunk) {}

            @Override
            public void close() {}
        };

        private Noop() {}

        @Override
        public ToolOutputPreviewSubscription subscribe(AgentRunId runId, Consumer<ToolOutputPreview> consumer) {
            Objects.requireNonNull(runId, "runId must not be null");
            Objects.requireNonNull(consumer, "consumer must not be null");
            return SUBSCRIPTION;
        }

        @Override
        public ToolOutputPreviewSink open(AgentRunId runId, ToolCallId toolCallId) {
            Objects.requireNonNull(runId, "runId must not be null");
            Objects.requireNonNull(toolCallId, "toolCallId must not be null");
            return SINK;
        }
    }
}

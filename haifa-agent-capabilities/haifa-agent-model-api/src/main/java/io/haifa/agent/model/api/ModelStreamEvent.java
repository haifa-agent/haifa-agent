package io.haifa.agent.model.api;

import java.util.Objects;

/** Provider-neutral deltas for one physical model call. Reasoning events are sensitive and internal-only. */
public sealed interface ModelStreamEvent
        permits ModelStreamEvent.Started,
                ModelStreamEvent.ReasoningDelta,
                ModelStreamEvent.ContentDelta,
                ModelStreamEvent.ToolCallDelta,
                ModelStreamEvent.UsageReported {

    ModelCallId callId();

    long eventIndex();

    record Started(ModelCallId callId, long eventIndex) implements ModelStreamEvent {
        public Started {
            require(callId, eventIndex);
        }
    }

    record ReasoningDelta(ModelCallId callId, long eventIndex, String delta) implements ModelStreamEvent {
        public ReasoningDelta {
            require(callId, eventIndex);
            delta = requireContent(delta, "reasoning delta");
        }

        @Override
        public String toString() {
            return "ReasoningDelta[callId=" + callId + ", eventIndex=" + eventIndex + ", delta=[REDACTED]]";
        }
    }

    record ContentDelta(ModelCallId callId, long eventIndex, String delta) implements ModelStreamEvent {
        public ContentDelta {
            require(callId, eventIndex);
            delta = requireContent(delta, "content delta");
        }

        @Override
        public String toString() {
            return "ContentDelta[callId=" + callId + ", eventIndex=" + eventIndex + ", chars=" + delta.length() + "]";
        }
    }

    record ToolCallDelta(
            ModelCallId callId,
            long eventIndex,
            int choiceIndex,
            int toolIndex,
            String correlationId,
            String name,
            String argumentsFragment)
            implements ModelStreamEvent {
        public ToolCallDelta {
            require(callId, eventIndex);
            if (choiceIndex < 0 || toolIndex < 0) {
                throw new IllegalArgumentException("choiceIndex and toolIndex must not be negative");
            }
            correlationId = Objects.requireNonNull(correlationId, "correlationId must not be null");
            name = Objects.requireNonNull(name, "name must not be null");
            argumentsFragment = Objects.requireNonNull(argumentsFragment, "argumentsFragment must not be null");
        }

        @Override
        public String toString() {
            return "ToolCallDelta[callId=" + callId + ", eventIndex=" + eventIndex + ", choiceIndex=" + choiceIndex
                    + ", toolIndex=" + toolIndex + ", arguments=[REDACTED]]";
        }
    }

    record UsageReported(ModelCallId callId, long eventIndex, ModelUsage usage) implements ModelStreamEvent {
        public UsageReported {
            require(callId, eventIndex);
            usage = Objects.requireNonNull(usage, "usage must not be null");
        }
    }

    private static void require(ModelCallId callId, long eventIndex) {
        Objects.requireNonNull(callId, "callId must not be null");
        if (eventIndex < 1) throw new IllegalArgumentException("eventIndex must be positive");
    }

    private static String requireContent(String value, String field) {
        String result = Objects.requireNonNull(value, field + " must not be null");
        if (result.isEmpty()) throw new IllegalArgumentException(field + " must not be empty");
        return result;
    }
}

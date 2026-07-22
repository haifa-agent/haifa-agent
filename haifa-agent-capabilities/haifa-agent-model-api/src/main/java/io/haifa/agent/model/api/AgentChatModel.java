package io.haifa.agent.model.api;

/** Synchronous provider-neutral model invocation boundary. */
@FunctionalInterface
public interface AgentChatModel {
    AgentChatResponse invoke(AgentChatRequest request);

    /**
     * Invokes a model while reporting provider-neutral deltas. The default preserves compatibility with synchronous
     * adapters by emitting one bounded content delta after the invocation completes.
     */
    default AgentChatResponse invokeStreaming(AgentChatRequest request, ModelStreamSink sink) {
        java.util.Objects.requireNonNull(request, "request must not be null");
        java.util.Objects.requireNonNull(sink, "sink must not be null");
        if (sink.emit(new ModelStreamEvent.Started(request.callId(), 1)) == ModelStreamControl.CANCEL) {
            throw new ModelInvocationException(
                    ModelErrorCategory.CANCELLED,
                    false,
                    0,
                    "stream_cancelled",
                    request.callId(),
                    "model stream was cancelled",
                    null);
        }
        AgentChatResponse response = invoke(request);
        long eventIndex = 2;
        if (!response.content().isEmpty()
                && sink.emit(new ModelStreamEvent.ContentDelta(request.callId(), eventIndex++, response.content()))
                        == ModelStreamControl.CANCEL) {
            // The synchronous adapter has already completed, so the Runtime safe point owns the pending control.
            return response;
        }
        sink.emit(new ModelStreamEvent.UsageReported(request.callId(), eventIndex, response.usage()));
        return response;
    }
}

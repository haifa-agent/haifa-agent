package io.haifa.agent.personalassistant.server.observability;

import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ImageDataPart;
import io.haifa.agent.model.api.ImageUrlPart;
import io.haifa.agent.model.api.ModelInvocationException;
import io.haifa.agent.model.api.ModelStreamSink;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spring Boot logging decorator for model calls.
 *
 * <p>Only operational metadata is logged. Messages, Tool arguments, model content, credentials,
 * endpoints, and exception messages are intentionally excluded.
 */
public final class LoggingAgentChatModel implements AgentChatModel {
    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingAgentChatModel.class);
    private final AgentChatModel delegate;

    public LoggingAgentChatModel(AgentChatModel delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    @Override
    public AgentChatResponse invoke(AgentChatRequest request) {
        return invokeLogged(request, () -> delegate.invoke(request));
    }

    @Override
    public AgentChatResponse invokeStreaming(AgentChatRequest request, ModelStreamSink sink) {
        Objects.requireNonNull(sink, "sink must not be null");
        return invokeLogged(request, () -> delegate.invokeStreaming(request, sink));
    }

    private static AgentChatResponse invokeLogged(AgentChatRequest request, Supplier<AgentChatResponse> invocation) {
        Objects.requireNonNull(request, "request must not be null");
        long started = System.nanoTime();
        MediaInputSummary mediaInputs = summarizeMediaInputs(request);
        LOGGER.info(
                "event=model.call.started runId={} callId={} iteration={} attempt={} messageCount={} toolCount={} imageUrlCount={} imageDataCount={} imageDataBytes={} timeoutMillis={}",
                request.runId().value(),
                request.callId().value(),
                request.iteration(),
                request.attempt(),
                request.messages().size(),
                request.tools().size(),
                mediaInputs.imageUrlCount(),
                mediaInputs.imageDataCount(),
                mediaInputs.imageDataBytes(),
                request.timeout().toMillis());
        try {
            AgentChatResponse response = invocation.get();
            LOGGER.info(
                    "event=model.call.completed runId={} callId={} finishReason={} toolCallCount={} inputTokens={} outputTokens={} cachedTokens={} durationMillis={}",
                    request.runId().value(),
                    request.callId().value(),
                    response.finishReason(),
                    response.toolCalls().size(),
                    response.usage().inputTokens(),
                    response.usage().outputTokens(),
                    response.usage().cacheHitTokens(),
                    elapsedMillis(started));
            return response;
        } catch (ModelInvocationException failure) {
            LOGGER.warn(
                    "event=model.call.failed runId={} callId={} failureType={} category={} retryable={} httpStatus={} providerCode={} safeMessage={} durationMillis={}",
                    request.runId().value(),
                    request.callId().value(),
                    failure.getClass().getName(),
                    failure.category(),
                    failure.retryable(),
                    failure.httpStatus(),
                    failure.providerCode(),
                    failure.getMessage(),
                    elapsedMillis(started),
                    failure);
            throw failure;
        } catch (RuntimeException failure) {
            LOGGER.warn(
                    "event=model.call.failed runId={} callId={} failureType={} durationMillis={}",
                    request.runId().value(),
                    request.callId().value(),
                    failure.getClass().getName(),
                    elapsedMillis(started));
            throw failure;
        }
    }

    private static long elapsedMillis(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private static MediaInputSummary summarizeMediaInputs(AgentChatRequest request) {
        int imageUrlCount = 0;
        int imageDataCount = 0;
        long imageDataBytes = 0;
        for (var message : request.messages()) {
            for (var image : message.images()) {
                if (image instanceof ImageUrlPart) {
                    imageUrlCount++;
                } else if (image instanceof ImageDataPart data) {
                    imageDataCount++;
                    imageDataBytes += data.sizeBytes();
                }
            }
        }
        return new MediaInputSummary(imageUrlCount, imageDataCount, imageDataBytes);
    }

    /** Bounded request-shape telemetry; URLs, image bytes, and message content are intentionally excluded. */
    private record MediaInputSummary(int imageUrlCount, int imageDataCount, long imageDataBytes) {}
}

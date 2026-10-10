package io.haifa.agent.runtime.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunOutcome;
import io.haifa.agent.core.run.StructuredOutputRequirement;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelCallId;
import io.haifa.agent.model.api.ModelErrorCategory;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelInvocationException;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelRequestId;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.runtime.api.TruncatedOutputPolicy;
import io.haifa.agent.runtime.core.bootstrap.DefaultResolvedModelSnapshots;
import io.haifa.agent.runtime.core.decision.FinalAnswerDecision;
import io.haifa.agent.runtime.core.decision.ToolCallDecision;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class AgentChatResponseMapperTruncatedPolicyTest {
    private AgentChatResponseMapper mapper;
    private AgentChatRequest request;
    private ModelToolSpecification sampleTool;

    @BeforeEach
    void setUp() {
        AtomicLong ids = new AtomicLong();
        mapper = new AgentChatResponseMapper(() -> "id-" + ids.incrementAndGet());
        sampleTool = new ModelToolSpecification(
                "echo", "1.0", "Echoes text", "echo.input", "1.0", Map.of("type", "object"), true);
        request = new AgentChatRequest(
                new ModelCallId("call-1"),
                new ModelRequestId("req-1"),
                new AgentRunId("run-1"),
                1,
                1,
                DefaultResolvedModelSnapshots.deepSeekV4Pro(),
                List.of(new ModelMessage(
                        ModelMessageRole.USER, "Answer briefly", List.of(), Optional.empty(), Map.of(), false)),
                List.of(sampleTool),
                2048,
                Duration.ofSeconds(10),
                Map.of(),
                Optional.empty());
    }

    @Test
    void defaultFailClosedRejectsPlainLengthAsOutputTruncated() {
        assertThatMapperIsRejected(
                TruncatedOutputPolicy.FAIL_CLOSED,
                response("Partial plain answer", ModelFinishReason.LENGTH),
                ModelErrorCategory.MALFORMED_RESPONSE,
                "output_truncated",
                null);
    }

    @Test
    void defaultFailClosedPreservesToolExecutionBeforeLengthCheck() {
        var decision = mapper.map(
                request,
                response(
                        "",
                        ModelFinishReason.LENGTH,
                        ModelUsage.unpriced(10, 200),
                        new ModelToolCall(
                                new ProviderToolCallCorrelationId("call-1"), "echo", Map.of("text", "hello"))),
                List.of(sampleTool),
                TruncatedOutputPolicy.FAIL_CLOSED);
        assertThat(decision).isInstanceOf(ToolCallDecision.class);
    }

    @Test
    void optInAcceptsNonemptyPlainLengthWithWarning() {
        String summary = "Complete explanation of primes up to 2048 tokens...";

        var decision = mapper.map(
                request,
                response(summary, ModelFinishReason.LENGTH),
                List.of(sampleTool),
                TruncatedOutputPolicy.ACCEPT_NONEMPTY_PLAIN_TEXT);

        assertThat(decision).isInstanceOf(FinalAnswerDecision.class);
        FinalAnswerDecision answer = (FinalAnswerDecision) decision;
        assertThat(answer.outcome()).isEqualTo(AgentRunOutcome.SUCCESS);
        assertThat(answer.summary()).isEqualTo(summary);
        assertThat(answer.warnings()).containsExactly(AgentChatResponseMapper.TRUNCATED_LENGTH_WARNING);
    }

    @Test
    void optInRejectsLengthWithToolCallsBeforeExecution() {
        assertThatMapperIsRejected(
                TruncatedOutputPolicy.ACCEPT_NONEMPTY_PLAIN_TEXT,
                response(
                        "Partial text",
                        ModelFinishReason.LENGTH,
                        new ModelToolCall(
                                new ProviderToolCallCorrelationId("call-1"), "echo", Map.of("text", "half-baked"))),
                "output_truncated");
    }

    @Test
    void optInRejectsLengthWithStructuredOutput() {
        assertModelInvocation(
                () -> mapper.map(
                        structuredRequest(),
                        response("{\"key\": \"val", ModelFinishReason.LENGTH),
                        List.of(),
                        TruncatedOutputPolicy.ACCEPT_NONEMPTY_PLAIN_TEXT),
                null,
                "output_truncated",
                null);
    }

    @Test
    void optInRejectsBlankOrReasoningOnlyLengthAsEmptyResponse() {
        assertThatMapperIsRejected(
                TruncatedOutputPolicy.ACCEPT_NONEMPTY_PLAIN_TEXT,
                response("   ", ModelFinishReason.LENGTH),
                ModelErrorCategory.EMPTY_RESPONSE,
                "empty_response",
                true);
    }

    @Test
    void optInRejectsContentFilterAndUnknownFinishReasons() {
        assertThatMapperIsRejected(
                TruncatedOutputPolicy.ACCEPT_NONEMPTY_PLAIN_TEXT,
                response("Content blocked", ModelFinishReason.CONTENT_FILTER, ModelUsage.unpriced(10, 100)),
                ModelErrorCategory.CONTENT_REJECTED,
                "content_filter",
                null);
        assertThatMapperIsRejected(
                TruncatedOutputPolicy.ACCEPT_NONEMPTY_PLAIN_TEXT,
                response("Unknown state", ModelFinishReason.UNKNOWN, ModelUsage.unpriced(10, 100)),
                ModelErrorCategory.UNKNOWN_PROVIDER_ERROR,
                "unknown_finish_reason",
                null);
    }

    private void assertThatMapperIsRejected(
            TruncatedOutputPolicy policy,
            AgentChatResponse response,
            ModelErrorCategory category,
            String providerCode,
            Boolean retryable) {
        assertModelInvocation(
                () -> mapper.map(request, response, List.of(sampleTool), policy), category, providerCode, retryable);
    }

    private void assertThatMapperIsRejected(
            TruncatedOutputPolicy policy, AgentChatResponse response, String providerCode) {
        assertModelInvocation(
                () -> mapper.map(request, response, List.of(sampleTool), policy), null, providerCode, null);
    }

    private static void assertModelInvocation(
            ThrowingCallable call, ModelErrorCategory category, String providerCode, Boolean retryable) {
        assertThatThrownBy(call).isInstanceOf(ModelInvocationException.class).satisfies(ex -> {
            var error = (ModelInvocationException) ex;
            if (category != null) {
                assertThat(error.category()).isEqualTo(category);
            }
            assertThat(error.providerCode()).isEqualTo(providerCode);
            if (retryable != null) {
                assertThat(error.retryable()).isTrue();
            }
        });
    }

    private AgentChatRequest structuredRequest() {
        return new AgentChatRequest(
                request.callId(),
                request.requestId(),
                request.runId(),
                1,
                1,
                request.model(),
                request.messages(),
                List.of(),
                2048,
                Duration.ofSeconds(10),
                Map.of(),
                Optional.of(new StructuredOutputRequirement("schema-1", "1.0", "target", Map.of("type", "object"))));
    }

    private static AgentChatResponse response(
            String content, ModelFinishReason finishReason, ModelToolCall... toolCalls) {
        return response(content, finishReason, ModelUsage.unpriced(10, 2048), toolCalls);
    }

    private static AgentChatResponse response(
            String content, ModelFinishReason finishReason, ModelUsage usage, ModelToolCall... toolCalls) {
        return new AgentChatResponse(
                "resp-1", "deepseek-v4-pro", content, List.of(toolCalls), finishReason, usage, "", Map.of());
    }
}

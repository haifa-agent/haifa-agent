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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class AgentChatResponseMapperTruncatedPolicyTest {
    private AgentChatResponseMapper mapper;
    private AgentChatRequest request;
    private ModelToolSpecification sampleTool;

    @BeforeEach
    void setUp() {
        java.util.concurrent.atomic.AtomicLong ids = new java.util.concurrent.atomic.AtomicLong();
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
        AgentChatResponse response = new AgentChatResponse(
                "resp-1",
                "deepseek-v4-pro",
                "Partial plain answer",
                List.of(),
                ModelFinishReason.LENGTH,
                ModelUsage.unpriced(10, 2048),
                "",
                Map.of());

        assertThatThrownBy(() -> mapper.map(request, response, List.of(sampleTool), TruncatedOutputPolicy.FAIL_CLOSED))
                .isInstanceOf(ModelInvocationException.class)
                .satisfies(ex -> {
                    var error = (ModelInvocationException) ex;
                    assertThat(error.category()).isEqualTo(ModelErrorCategory.MALFORMED_RESPONSE);
                    assertThat(error.providerCode()).isEqualTo("output_truncated");
                });
    }

    @Test
    void defaultFailClosedPreservesToolExecutionBeforeLengthCheck() {
        AgentChatResponse response = new AgentChatResponse(
                "resp-1",
                "deepseek-v4-pro",
                "",
                List.of(new ModelToolCall(
                        new ProviderToolCallCorrelationId("call-1"), "echo", Map.of("text", "hello"))),
                ModelFinishReason.LENGTH,
                ModelUsage.unpriced(10, 200),
                "",
                Map.of());

        var decision = mapper.map(request, response, List.of(sampleTool), TruncatedOutputPolicy.FAIL_CLOSED);
        assertThat(decision).isInstanceOf(ToolCallDecision.class);
    }

    @Test
    void optInAcceptsNonemptyPlainLengthWithWarning() {
        AgentChatResponse response = new AgentChatResponse(
                "resp-1",
                "deepseek-v4-pro",
                "Complete explanation of primes up to 2048 tokens...",
                List.of(),
                ModelFinishReason.LENGTH,
                ModelUsage.unpriced(10, 2048),
                "",
                Map.of());

        var decision =
                mapper.map(request, response, List.of(sampleTool), TruncatedOutputPolicy.ACCEPT_NONEMPTY_PLAIN_TEXT);

        assertThat(decision).isInstanceOf(FinalAnswerDecision.class);
        FinalAnswerDecision answer = (FinalAnswerDecision) decision;
        assertThat(answer.outcome()).isEqualTo(AgentRunOutcome.SUCCESS);
        assertThat(answer.summary()).isEqualTo("Complete explanation of primes up to 2048 tokens...");
        assertThat(answer.warnings()).containsExactly(AgentChatResponseMapper.TRUNCATED_LENGTH_WARNING);
    }

    @Test
    void optInRejectsLengthWithToolCallsBeforeExecution() {
        AgentChatResponse response = new AgentChatResponse(
                "resp-1",
                "deepseek-v4-pro",
                "Partial text",
                List.of(new ModelToolCall(
                        new ProviderToolCallCorrelationId("call-1"), "echo", Map.of("text", "half-baked"))),
                ModelFinishReason.LENGTH,
                ModelUsage.unpriced(10, 2048),
                "",
                Map.of());

        assertThatThrownBy(() -> mapper.map(
                        request, response, List.of(sampleTool), TruncatedOutputPolicy.ACCEPT_NONEMPTY_PLAIN_TEXT))
                .isInstanceOf(ModelInvocationException.class)
                .satisfies(ex -> {
                    var error = (ModelInvocationException) ex;
                    assertThat(error.providerCode()).isEqualTo("output_truncated");
                });
    }

    @Test
    void optInRejectsLengthWithStructuredOutput() {
        AgentChatRequest structuredRequest = new AgentChatRequest(
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

        AgentChatResponse response = new AgentChatResponse(
                "resp-1",
                "deepseek-v4-pro",
                "{\"key\": \"val",
                List.of(),
                ModelFinishReason.LENGTH,
                ModelUsage.unpriced(10, 2048),
                "",
                Map.of());

        assertThatThrownBy(() -> mapper.map(
                        structuredRequest, response, List.of(), TruncatedOutputPolicy.ACCEPT_NONEMPTY_PLAIN_TEXT))
                .isInstanceOf(ModelInvocationException.class)
                .satisfies(ex -> {
                    var error = (ModelInvocationException) ex;
                    assertThat(error.providerCode()).isEqualTo("output_truncated");
                });
    }

    @Test
    void optInRejectsBlankOrReasoningOnlyLengthAsEmptyResponse() {
        AgentChatResponse response = new AgentChatResponse(
                "resp-1",
                "deepseek-v4-pro",
                "   ",
                List.of(),
                ModelFinishReason.LENGTH,
                ModelUsage.unpriced(10, 2048),
                "",
                Map.of());

        assertThatThrownBy(() -> mapper.map(
                        request, response, List.of(sampleTool), TruncatedOutputPolicy.ACCEPT_NONEMPTY_PLAIN_TEXT))
                .isInstanceOf(ModelInvocationException.class)
                .satisfies(ex -> {
                    var error = (ModelInvocationException) ex;
                    assertThat(error.category()).isEqualTo(ModelErrorCategory.EMPTY_RESPONSE);
                    assertThat(error.providerCode()).isEqualTo("empty_response");
                    assertThat(error.retryable()).isTrue();
                });
    }

    @Test
    void optInRejectsContentFilterAndUnknownFinishReasons() {
        AgentChatResponse filterResponse = new AgentChatResponse(
                "resp-1",
                "deepseek-v4-pro",
                "Content blocked",
                List.of(),
                ModelFinishReason.CONTENT_FILTER,
                ModelUsage.unpriced(10, 100),
                "",
                Map.of());

        assertThatThrownBy(() -> mapper.map(
                        request, filterResponse, List.of(sampleTool), TruncatedOutputPolicy.ACCEPT_NONEMPTY_PLAIN_TEXT))
                .isInstanceOf(ModelInvocationException.class)
                .satisfies(ex -> {
                    var error = (ModelInvocationException) ex;
                    assertThat(error.category()).isEqualTo(ModelErrorCategory.CONTENT_REJECTED);
                    assertThat(error.providerCode()).isEqualTo("content_filter");
                });

        AgentChatResponse unknownResponse = new AgentChatResponse(
                "resp-2",
                "deepseek-v4-pro",
                "Unknown state",
                List.of(),
                ModelFinishReason.UNKNOWN,
                ModelUsage.unpriced(10, 100),
                "",
                Map.of());

        assertThatThrownBy(() -> mapper.map(
                        request,
                        unknownResponse,
                        List.of(sampleTool),
                        TruncatedOutputPolicy.ACCEPT_NONEMPTY_PLAIN_TEXT))
                .isInstanceOf(ModelInvocationException.class)
                .satisfies(ex -> {
                    var error = (ModelInvocationException) ex;
                    assertThat(error.category()).isEqualTo(ModelErrorCategory.UNKNOWN_PROVIDER_ERROR);
                    assertThat(error.providerCode()).isEqualTo("unknown_finish_reason");
                });
    }
}

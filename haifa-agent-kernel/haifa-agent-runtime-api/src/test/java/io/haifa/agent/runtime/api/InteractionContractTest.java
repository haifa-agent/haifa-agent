package io.haifa.agent.runtime.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.content.ToolCallPart;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.ToolCallId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class InteractionContractTest {
    @Test
    void keepsUnknownInteractionValuesReadableButDistinguishable() {
        assertThat(new InteractionKind("future-review").known()).isFalse();
        assertThat(new InteractionInputType("future-input").known()).isFalse();
        assertThat(new InteractionAction("future-action").value()).isEqualTo("future-action");
    }

    @Test
    void enforcesBoundedInputContracts() {
        assertThat(InteractionInputContract.text(2_048).type()).isEqualTo(InteractionInputType.TEXT);
        assertThatThrownBy(() -> new InteractionInputContract(
                        InteractionInputType.SINGLE_CHOICE,
                        0,
                        0,
                        2,
                        0,
                        0,
                        List.of(new InteractionOption("one", "One")),
                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InteractionInputContract(
                        InteractionInputType.SCHEMA_REF, 0, 0, 0, 0, 128, List.of(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void permitsAnInteractionWithoutAnAutomaticExpiry() {
        Instant createdAt = Instant.parse("2026-07-26T00:00:00Z");
        InteractionView view = new InteractionView(
                new InteractionRequestId("request-1"),
                new AgentRunId("run-1"),
                new io.haifa.agent.core.session.AgentSessionId("session-1"),
                0,
                InteractionKind.CLARIFICATION,
                InteractionState.PENDING,
                "Input required",
                "Provide a safe value",
                List.of(InteractionAction.SUBMIT),
                InteractionInputContract.text(128),
                new InteractionTargetView("interaction", "clarification", Optional.empty(), Optional.empty(), "Input"),
                new InteractionRequesterView("user", "requester"),
                createdAt,
                Optional.empty(),
                new InteractionConsequenceView("Continue", "Stop", "No automatic expiry"));

        assertThat(view.expiresAt()).isEmpty();
    }

    @Test
    void rejectsToolProtocolPartsFromSteerInput() {
        ToolCallPart toolCall = new ToolCallPart(
                new ToolCallId("tool-call"), new ProviderToolCallCorrelationId("provider-call"), "echo", "1.0.0");

        assertThatThrownBy(() -> new RunInputSubmission(
                        new RunInputId("input-1"),
                        new AgentRunId("run-1"),
                        OptionalLong.empty(),
                        List.of(toolCall),
                        "input-key",
                        Instant.parse("2026-07-26T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tool protocol");
        assertThatThrownBy(() -> new InteractionResponseSubmission(
                        new InteractionResponseId("response-1"),
                        new InteractionRequestId("request-1"),
                        new AgentRunId("run-1"),
                        0,
                        InteractionAction.SUBMIT,
                        List.of(toolCall),
                        "response-key",
                        Instant.parse("2026-07-26T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tool protocol");

        assertThat(new RunInputSubmission(
                                new RunInputId("input-2"),
                                new AgentRunId("run-1"),
                                OptionalLong.empty(),
                                List.of(new TextPart("please adjust", "text/plain")),
                                "input-key-2",
                                Instant.parse("2026-07-26T00:00:00Z"))
                        .contents())
                .hasSize(1);
    }

    @Test
    void requiresDottedEventTypesAndMatchingCursor() {
        AgentRunId runId = new AgentRunId("run-1");
        RunEventCursor cursor = new RunEventCursor(runId, "1", OptionalLong.of(1));
        AgentRunEvent event = new AgentRunEvent(
                "event-1",
                "run.status.changed",
                "1",
                runId,
                new io.haifa.agent.core.session.AgentSessionId("session-1"),
                1,
                cursor,
                Instant.parse("2026-07-26T00:00:00Z"),
                Optional.empty(),
                Optional.empty(),
                new TestPayload());

        assertThat(event.eventType()).isEqualTo("run.status.changed");
        assertThatThrownBy(() -> new AgentRunEvent(
                        "event-2",
                        "invalid",
                        "1",
                        runId,
                        new io.haifa.agent.core.session.AgentSessionId("session-1"),
                        1,
                        cursor,
                        Instant.parse("2026-07-26T00:00:00Z"),
                        Optional.empty(),
                        Optional.empty(),
                        new TestPayload()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private record TestPayload() implements AgentRunEvent.Payload {}
}

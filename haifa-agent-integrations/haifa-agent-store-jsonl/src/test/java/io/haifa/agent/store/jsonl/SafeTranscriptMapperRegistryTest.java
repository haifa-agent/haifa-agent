package io.haifa.agent.store.jsonl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.core.storage.OutboxMessage;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SafeTranscriptMapperRegistryTest {
    private static final Instant NOW = Instant.parse("2026-07-25T08:00:00Z");

    @Test
    void selectsOnlyApprovedFieldsAndDropsOpaqueSourceContent() {
        OutboxMessage source = message(
                "run.completed",
                "1",
                Map.of(
                        "status", "COMPLETED",
                        "version", 4,
                        "apiKey", "sk-must-never-leave-sqlite",
                        "prompt", "raw prompt",
                        "providerResponse", Map.of("raw", true)));

        SafeTranscriptEvent safe = new TranscriptRedactor()
                .redact(SafeTranscriptMapperRegistry.defaults().map(source));

        assertThat(safe.payload()).containsExactlyInAnyOrderEntriesOf(Map.of("status", "COMPLETED", "version", 4));
        assertThat(safe.toString()).doesNotContain("sk-must", "raw prompt", "providerResponse");
    }

    @Test
    void mapsInteractionApprovalPolicyAndInputEventsWithExplicitSafeFields() {
        var registry = SafeTranscriptMapperRegistry.defaults();

        assertThat(registry.map(message(
                                "approval.requested",
                                "1",
                                Map.of(
                                        "requestId",
                                        "request-1",
                                        "decisionId",
                                        "decision-1",
                                        "challenge",
                                        "APPROVAL",
                                        "semantics",
                                        "CAPABILITY_CONFIRMATION",
                                        "targetPayload",
                                        "must-not-project")))
                        .payload())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "requestId",
                        "request-1",
                        "decisionId",
                        "decision-1",
                        "challenge",
                        "APPROVAL",
                        "semantics",
                        "CAPABILITY_CONFIRMATION"));
        assertThat(registry.map(message(
                                "approval.responded",
                                "1",
                                Map.of(
                                        "requestId",
                                        "request-1",
                                        "responseType",
                                        "REJECT",
                                        "responder",
                                        "must-not-project")))
                        .payload())
                .containsExactlyInAnyOrderEntriesOf(Map.of("requestId", "request-1", "responseType", "REJECT"));
        assertThat(registry.map(message(
                                "approval.authority.verified",
                                "1",
                                Map.of(
                                        "requestId",
                                        "request-1",
                                        "responseId",
                                        "response-1",
                                        "outcome",
                                        "ACCEPTED",
                                        "reasonCode",
                                        "AUTHORITY_VERIFIED")))
                        .payload())
                .containsEntry("outcome", "ACCEPTED");
        assertThat(registry.map(message(
                                "approval.target.validated",
                                "1",
                                Map.of(
                                        "requestId",
                                        "request-1",
                                        "responseId",
                                        "response-1",
                                        "outcome",
                                        "CURRENT",
                                        "reasonCode",
                                        "TARGET_CURRENT")))
                        .payload())
                .containsEntry("outcome", "CURRENT");
        assertThat(registry.map(message(
                                "policy.decision.made",
                                "1",
                                Map.of(
                                        "decisionId",
                                        "decision-1",
                                        "snapshotId",
                                        "snapshot-internal",
                                        "effect",
                                        "ASK",
                                        "challenge",
                                        "APPROVAL",
                                        "reasonCode",
                                        "NETWORK_REQUIRES_APPROVAL")))
                        .payload())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "decisionId",
                        "decision-1",
                        "effect",
                        "ASK",
                        "challenge",
                        "APPROVAL",
                        "reasonCode",
                        "NETWORK_REQUIRES_APPROVAL"));
        assertThat(registry.map(message(
                                "interaction.requested",
                                "1",
                                Map.of("requestId", "request-2", "kind", "clarification")))
                        .payload())
                .containsExactlyInAnyOrderEntriesOf(Map.of("requestId", "request-2", "kind", "clarification"));
        assertThat(registry.map(message(
                                "interaction.expired", "1", Map.of("requestId", "request-2", "outcome", "FAIL_RUN")))
                        .payload())
                .containsExactlyInAnyOrderEntriesOf(Map.of("requestId", "request-2", "outcome", "FAIL_RUN"));
        assertThat(registry.map(message("run.input.accepted", "1", Map.of("inputId", "input-1", "kind", "steer")))
                        .payload())
                .containsExactlyInAnyOrderEntriesOf(Map.of("inputId", "input-1", "kind", "steer"));
        assertThat(registry.map(message(
                                "run.input.applied",
                                "1",
                                Map.of(
                                        "inputId",
                                        "input-1",
                                        "attemptId",
                                        "attempt-2",
                                        "iteration",
                                        3,
                                        "safePoint",
                                        "BEFORE_ITERATION")))
                        .payload())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "inputId",
                        "input-1",
                        "attemptId",
                        "attempt-2",
                        "iteration",
                        3,
                        "safePoint",
                        "BEFORE_ITERATION"));
    }

    @Test
    void unknownTypeAndSchemaFailClosed() {
        assertThatThrownBy(() -> SafeTranscriptMapperRegistry.defaults()
                        .map(message("model.raw-response", "1", Map.of("response", "secret"))))
                .isInstanceOf(TranscriptProjectionException.class)
                .extracting(exception -> ((TranscriptProjectionException) exception).code())
                .isEqualTo(TranscriptDiagnosticCode.UNKNOWN_EVENT_TYPE);

        assertThatThrownBy(() -> SafeTranscriptMapperRegistry.defaults()
                        .map(message("run.completed", "999", Map.of("status", "COMPLETED"))))
                .isInstanceOf(TranscriptProjectionException.class)
                .extracting(exception -> ((TranscriptProjectionException) exception).code())
                .isEqualTo(TranscriptDiagnosticCode.UNSUPPORTED_SCHEMA);
    }

    @Test
    void defenseInDepthRejectsCredentialShapesAndForbiddenFields() {
        TranscriptRedactor redactor = new TranscriptRedactor();

        assertThatThrownBy(() -> redactor.redact(event(Map.of("summary", "Bearer abcdefghijklmnop"))))
                .isInstanceOf(TranscriptProjectionException.class)
                .extracting(exception -> ((TranscriptProjectionException) exception).code())
                .isEqualTo(TranscriptDiagnosticCode.UNSAFE_PAYLOAD);
        assertThatThrownBy(() -> redactor.redact(event(Map.of("reasoning-content", "hidden"))))
                .isInstanceOf(TranscriptProjectionException.class)
                .extracting(exception -> ((TranscriptProjectionException) exception).code())
                .isEqualTo(TranscriptDiagnosticCode.UNSAFE_PAYLOAD);
    }

    private static OutboxMessage message(String type, String schema, Map<String, Object> payload) {
        return new OutboxMessage("event-1", new AgentRunId("run-1"), 1, type, schema, payload, NOW);
    }

    private static SafeTranscriptEvent event(Map<String, Object> payload) {
        return new SafeTranscriptEvent("1", "event-1", "run-1", 1, NOW, "run.completed", payload);
    }
}

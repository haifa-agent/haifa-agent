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

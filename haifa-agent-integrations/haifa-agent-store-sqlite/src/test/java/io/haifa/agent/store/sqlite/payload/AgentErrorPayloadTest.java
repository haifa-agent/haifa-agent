package io.haifa.agent.store.sqlite.payload;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.error.AgentErrorCode;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentErrorPayloadTest {
    private static final Instant NOW = Instant.parse("2026-07-31T00:00:00Z");

    @Test
    void roundTripsCurrentErrorWithoutDependingOnLegacyDerivedFields() {
        AgentError original = new AgentError(
                AgentErrorCode.RUN_BUDGET_EXCEEDED,
                Map.of("resource", "modelCalls", "limit", 2L, "used", 2L),
                "diag-budget",
                NOW);

        AgentError restored = AgentErrorPayload.from(original).toDomain();

        assertThat(restored).isEqualTo(original);
    }

    @Test
    void readsUnknownLegacyCodeSafelyAndPreservesItsWireValue() {
        AgentError restored = new AgentErrorPayload(
                        "FUTURE_EXECUTION_ERROR",
                        "INTERNAL",
                        "ERROR",
                        "UNKNOWN",
                        "legacy message must not become display text",
                        "diag-legacy",
                        Map.of("operation", "fixture.read"),
                        NOW.toEpochMilli())
                .toDomain();

        assertThat(restored.code()).isEqualTo(AgentErrorCode.UNKNOWN);
        assertThat(restored.message()).isEqualTo("Unknown agent error");
        assertThat(restored.diagnosticId()).isEqualTo("diag-legacy");
        assertThat(restored.details())
                .containsEntry("operation", "fixture.read")
                .containsEntry("unrecognizedErrorCode", "FUTURE_EXECUTION_ERROR");
    }

    @Test
    void readsLegacyPayloadWithoutAttributesAsEmptyDetails() {
        AgentError restored = new AgentErrorPayload(
                        "RUNTIME_EXECUTION_FAILED",
                        "INTERNAL",
                        "ERROR",
                        "UNKNOWN",
                        "legacy internal message",
                        null,
                        null,
                        NOW.toEpochMilli())
                .toDomain();

        assertThat(restored.code()).isEqualTo(AgentErrorCode.RUNTIME_EXECUTION_FAILED);
        assertThat(restored.details()).isEmpty();
        assertThat(restored.diagnosticId()).isNull();
    }
}

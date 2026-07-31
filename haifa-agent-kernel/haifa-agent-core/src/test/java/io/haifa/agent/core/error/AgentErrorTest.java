package io.haifa.agent.core.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentErrorTest {
    private static final Instant NOW = Instant.parse("2026-07-31T00:00:00Z");

    @Test
    void catalogHasUniqueStableWireCodesAndRoundTrips() {
        assertThat(Arrays.stream(AgentErrorCode.values()).map(AgentErrorCode::wireCode))
                .doesNotHaveDuplicates()
                .allMatch(value -> value.matches("[A-Z][A-Z0-9_]*"));
        assertThat(Arrays.stream(AgentErrorCode.values()).map(AgentErrorCode::displayMessage))
                .allMatch(message -> !message.isBlank() && message.length() <= 128);
        assertThat(Arrays.stream(AgentErrorCode.values())
                        .filter(code -> code != AgentErrorCode.UNKNOWN)
                        .map(code -> AgentErrorCode.fromWireCode(code.wireCode())))
                .doesNotContain(AgentErrorCode.UNKNOWN);
        assertThat(AgentErrorCode.fromWireCode("FUTURE_ERROR")).isEqualTo(AgentErrorCode.UNKNOWN);
    }

    @Test
    void derivesStablePresentationAndRecoverySemanticsFromCode() {
        AgentError error = new AgentError(
                AgentErrorCode.RUN_BUDGET_EXCEEDED,
                Map.of("resource", "inputTokens", "limit", 256_000L, "used", 262_247L),
                "diag-1",
                NOW);

        assertThat(error.message()).isEqualTo("Run budget exceeded");
        assertThat(error.category()).isEqualTo(AgentErrorCategory.RESOURCE_LIMIT);
        assertThat(error.retryability()).isEqualTo(Retryability.NOT_RETRYABLE);
        assertThat(error.optionalDiagnosticId()).contains("diag-1");
        assertThat(error.details()).containsEntry("used", 262_247L).isUnmodifiable();
    }

    @Test
    void rejectsUnboundedOrUnsafeDetailValues() {
        assertThatThrownBy(() -> new AgentError(
                        AgentErrorCode.RUNTIME_EXECUTION_FAILED, Map.of("secret", "x".repeat(2_049)), "diag-1", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentError(
                        AgentErrorCode.RUNTIME_EXECUTION_FAILED, Map.of("unsafe", new Object()), "diag-1", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentError(
                        AgentErrorCode.RUNTIME_EXECUTION_FAILED,
                        Map.of("authorizationHeader", "canary-secret"),
                        "diag-1",
                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
    }
}

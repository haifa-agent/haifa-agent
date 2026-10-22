package io.haifa.agent.runtime.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.credential.api.SecretRedactor;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolIntentSummaryTest {

    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");

    @Test
    void extractsAndSanitizesDescription() {
        ToolCall call =
                call("file_read", Map.of("path", "/workspace/App.java", "description", "阅读 RuntimeCore.java\n第二行"));

        assertThat(ToolIntentSummary.from(call, SecretRedactor.noop())).isEqualTo("阅读 RuntimeCore.java 第二行");
    }

    @Test
    void returnsEmptyWhenDescriptionMissingBlankOrNonString() {
        assertThat(ToolIntentSummary.from(call("file_read", Map.of("path", "a.txt")), SecretRedactor.noop()))
                .isEmpty();
        assertThat(ToolIntentSummary.from(
                        call("file_read", Map.of("path", "a.txt", "description", "  ")), SecretRedactor.noop()))
                .isEmpty();
        assertThat(ToolIntentSummary.from(
                        call("file_read", Map.of("path", "a.txt", "description", 12)), SecretRedactor.noop()))
                .isEmpty();
        assertThat(ToolIntentSummary.from(null, SecretRedactor.noop())).isEmpty();
    }

    @Test
    void stripsControlCharactersBoundsLengthAndRedactsSecrets() {
        String longText = "查找 " + "a".repeat(300);
        assertThat(ToolIntentSummary.sanitize(longText))
                .hasSizeLessThanOrEqualTo(ToolIntentSummary.MAX_INTENT_LENGTH)
                .endsWith("…");

        assertThat(ToolIntentSummary.sanitize("运行 Authorization: Bearer sk-secret-token"))
                .isEqualTo("运行 Authorization: Bearer [REDACTED]");
        assertThat(ToolIntentSummary.sanitize("设置 api_key=super-secret")).isEqualTo("设置 api_key=[REDACTED]");
        assertThat(ToolIntentSummary.sanitize("打开 https://user:pass@example.com/docs"))
                .isEqualTo("打开 https://[REDACTED]@example.com/docs");
        assertThat(ToolIntentSummary.sanitize("safe\u0007text")).isEqualTo("safetext");
    }

    @Test
    void collapsesUnicodeLineSeparatorsToSingleLine() {
        assertThat(ToolIntentSummary.sanitize("第一行\u2028第二行\u2029第三行")).isEqualTo("第一行 第二行 第三行");
    }

    @Test
    void redactsKnownExactSecretValuesEvenInOrdinaryProse() {
        SecretRedactor redactor = value -> value == null ? null : value.replace("sk-live-prose-secret", "[REDACTED]");
        ToolCall call = call(
                "web_search",
                Map.of("query", "docs", "description", "Using sk-live-prose-secret to query the service"));

        assertThat(ToolIntentSummary.from(call, redactor)).isEqualTo("Using [REDACTED] to query the service");
    }

    @Test
    void doesNotReplaceTargetSummaryExtractionForExecutionCommand() {
        ToolCall call =
                call("execution_run", Map.of("command", "rg -l ToolCall .", "description", "查找包含 ToolCall 的文件"));

        assertThat(ToolIntentSummary.from(call, SecretRedactor.noop())).isEqualTo("查找包含 ToolCall 的文件");
        assertThat(ToolTargetSummary.from(call)).isEqualTo("rg -l ToolCall .");
    }

    private static ToolCall call(String toolName, Map<String, Object> arguments) {
        return new ToolCall(
                new ToolCallId("call-1"),
                new AgentRunId("run-1"),
                new AgentStepId("step-1"),
                new ProviderToolCallCorrelationId("provider-1"),
                new RuntimeIdempotencyKey("idem-1"),
                toolName,
                "1.0.0",
                new ToolArguments("schema", "1.0.0", arguments),
                NOW);
    }
}

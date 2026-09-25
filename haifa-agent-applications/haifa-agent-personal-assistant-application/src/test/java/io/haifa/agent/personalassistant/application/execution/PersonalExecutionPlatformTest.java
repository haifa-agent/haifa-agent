package io.haifa.agent.personalassistant.application.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.runtime.api.ApprovalPrompt;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.SemanticVersion;
import io.haifa.agent.tool.api.ToolAlias;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolCoordinate;
import io.haifa.agent.tool.api.ToolDefinition;
import io.haifa.agent.tool.api.ToolDefinitionHash;
import io.haifa.agent.tool.api.ToolExecutionMode;
import io.haifa.agent.tool.api.ToolIdempotency;
import io.haifa.agent.tool.api.ToolName;
import io.haifa.agent.tool.api.ToolProviderId;
import io.haifa.agent.tool.api.ToolResourceRequirements;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSchema;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PersonalExecutionPlatformTest {
    @Test
    void boundsLongExecutionContentWithoutDroppingApprovalIdentityOrRiskSummary() {
        String summary =
                """
                Approve execution
                Mode: SCRIPT
                Language: powershell
                Purpose: generate a word frequency report
                Invocation digest: sha256:test
                Risks: HIGH, PROCESS_EXECUTION, NON_IDEMPOTENT, host access; approve once or reject""";
        String content = "Write-Output 'word'\n".repeat(400);

        String prompt = PersonalExecutionPlatform.boundedContent(summary, content);

        assertThat(prompt)
                .hasSizeLessThanOrEqualTo(2_048)
                .contains(
                        "Mode: SCRIPT",
                        "Language: powershell",
                        "Invocation digest: sha256:test",
                        "Risks: HIGH",
                        "Full content:",
                        "Content truncated",
                        "original length=");
    }

    @Test
    void preservesCompleteShortExecutionContent() {
        String summary = "Approve execution\nInvocation digest: sha256:test";
        String content = "Get-Date";

        assertThat(PersonalExecutionPlatform.boundedContent(summary, content))
                .isEqualTo(summary + "\nFull content:\n" + content);
    }

    @Test
    void boundsStructuredPresentationFieldsFromModelArguments() {
        var platform = new PersonalExecutionPlatform(null, null, null, null);
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("mode", "COMMAND");
        arguments.put("language", "x".repeat(700));
        arguments.put("purpose", "p".repeat(700));
        arguments.put("content", "c".repeat(16_200));

        ApprovalPrompt prompt = platform.approvalPrompt(binding(), call(arguments), false);

        assertThat(prompt.presentation()).hasValueSatisfying(presentation -> {
            assertThat(presentation.title().length()).isLessThanOrEqualTo(256);
            assertThat(presentation.purpose().length()).isLessThanOrEqualTo(512);
            assertThat(presentation.contentType().length()).isLessThanOrEqualTo(64);
            assertThat(presentation.content().length()).isLessThanOrEqualTo(16_384);
            presentation.technical().forEach(fact -> assertThat(fact.value().length())
                    .isLessThanOrEqualTo(512));
        });
    }

    @Test
    void omitsStructuredPresentationWhenExecutionContentIsBlank() {
        var platform = new PersonalExecutionPlatform(null, null, null, null);
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("mode", "COMMAND");
        arguments.put("content", "\n");

        ApprovalPrompt prompt = platform.approvalPrompt(binding(), call(arguments), false);

        assertThat(prompt.prompt()).contains("Full content:");
        assertThat(prompt.presentation()).isEmpty();
    }

    private static ToolCall call(Map<String, Object> arguments) {
        return new ToolCall(
                new ToolCallId("call-1"),
                new AgentRunId("run-1"),
                new AgentStepId("step-1"),
                new ProviderToolCallCorrelationId("provider-call-1"),
                new RuntimeIdempotencyKey("key-1"),
                "execution_run",
                "3.0.0",
                new ToolArguments("haifa.execution.run.input", "3.0.0", arguments),
                Instant.parse("2026-07-28T00:00:00Z"));
    }

    private static FrozenToolBinding binding() {
        Map<String, Object> schema =
                Map.of("$schema", ToolSchema.DRAFT_2020_12, "type", "object", "additionalProperties", true);
        ToolDefinition definition = new ToolDefinition(
                new ToolName("execution_run"),
                new SemanticVersion("3.0.0"),
                new ToolProviderId("haifa-execution"),
                "Run execution",
                "Run a bounded execution",
                new ToolSchema("haifa.execution.run.input", "3.0.0", schema),
                new ToolSchema("haifa.execution.run.output", "3.0.0", schema),
                ToolExecutionMode.HOST_PROCESS,
                true,
                Duration.ofSeconds(30),
                "execution",
                ToolIdempotency.NON_IDEMPOTENT,
                ToolRisk.HIGH,
                Set.of(ToolSideEffect.PROCESS_EXECUTION),
                new ToolResourceRequirements(Set.of("execution_run"), Set.of(), Set.of("test@1")),
                List.of(),
                ToolApprovalRequirement.NEVER,
                "test",
                false,
                Set.of());
        return new FrozenToolBinding(
                new ToolAlias("execution_run"),
                new ToolCoordinate(
                        definition.name(),
                        definition.version(),
                        definition.providerId(),
                        new ToolDefinitionHash("1".repeat(64))),
                definition,
                "provider-binding",
                "catalog");
    }
}

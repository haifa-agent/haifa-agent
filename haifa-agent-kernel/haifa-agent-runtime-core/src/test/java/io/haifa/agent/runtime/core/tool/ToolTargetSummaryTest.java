package io.haifa.agent.runtime.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolTargetSummaryTest {

    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");

    @Test
    void extractsExecutionCommandAndNormalizesWhitespace() {
        ToolCall call = call(
                "execution_run", Map.of("command", "pwsh -File C:/Users/script.ps1 \\\n  -Arg1 val1\n  -Arg2 val2"));

        assertThat(ToolTargetSummary.from(call)).isEqualTo("pwsh -File C:/Users/script.ps1 \\ -Arg1 val1 -Arg2 val2");
    }

    @Test
    void extractsCommandLineCaseInsensitively() {
        ToolCall call = call("run_command", Map.of("CommandLine", "git status"));

        assertThat(ToolTargetSummary.from(call)).isEqualTo("git status");
    }

    @Test
    void preservesFullCommandWithoutRedaction() {
        ToolCall call = call(
                "execution_run",
                Map.of(
                        "command",
                        "curl -H Authorization: Bearer sk-ant-api03-abcdefghijklmn https://api.anthropic.com"));

        assertThat(ToolTargetSummary.from(call))
                .isEqualTo("curl -H Authorization: Bearer sk-ant-api03-abcdefghijklmn https://api.anthropic.com");
    }

    @Test
    void boundsTargetSummaryTo512Characters() {
        String longCommand = "echo " + "a".repeat(600);
        ToolCall call = call("execution_run", Map.of("command", longCommand));

        String summary = ToolTargetSummary.from(call);
        assertThat(summary).hasSizeLessThanOrEqualTo(512);
        assertThat(summary).endsWith("…");
    }

    @Test
    void extractsMoveSourceAndDestination() {
        ToolCall call = call("file_move", Map.of("sourcePath", "src/Old.java", "destinationPath", "src/New.java"));

        assertThat(ToolTargetSummary.from(call)).isEqualTo("src/Old.java -> src/New.java");
    }

    @Test
    void extractsFilePathFromPatchHeaderWhenExplicitPathMissing() {
        String patch =
                """
                *** Update File: src/main/java/App.java
                @@ -1,3 +1,3 @@
                """;
        ToolCall call = call("file_patch", Map.of("patch", patch));

        assertThat(ToolTargetSummary.from(call)).isEqualTo("src/main/java/App.java");
    }

    @Test
    void extractsDiffPathFromAddedFile() {
        String patch =
                """
                --- /dev/null
                +++ b/src/main/java/Created.java
                @@ -0,0 +1,5 @@
                """;
        ToolCall call = call("patch_file", Map.of("patch", patch));

        assertThat(ToolTargetSummary.from(call)).isEqualTo("src/main/java/Created.java");
    }

    @Test
    void extractsFilePathForFileOperations() {
        ToolCall readCall = call("view_file", Map.of("AbsolutePath", "d:/workspace/pom.xml"));
        assertThat(ToolTargetSummary.from(readCall)).isEqualTo("d:/workspace/pom.xml");

        ToolCall writeCall = call("write_to_file", Map.of("TargetFile", "src/App.java", "CodeContent", "code"));
        assertThat(ToolTargetSummary.from(writeCall)).isEqualTo("src/App.java");
    }

    @Test
    void extractsQueryAndUrl() {
        ToolCall search = call("web_search", Map.of("query", "java 21 pattern matching"));
        assertThat(ToolTargetSummary.from(search)).isEqualTo("java 21 pattern matching");

        ToolCall fetch = call("read_url_content", Map.of("Url", "https://example.com/docs"));
        assertThat(ToolTargetSummary.from(fetch)).isEqualTo("https://example.com/docs");
    }

    @Test
    void extractsAgentForDelegationTool() {
        ToolCall delegation = call("task", Map.of("agent", "researcher", "objective", "survey codebase"));
        assertThat(ToolTargetSummary.from(delegation)).isEqualTo("researcher");
    }

    @Test
    void fallsBackToToolNameWhenArgumentsEmptyOrMissing() {
        ToolCall emptyArgs = call("system_info", Map.of());
        assertThat(ToolTargetSummary.from(emptyArgs)).isEqualTo("system_info");

        assertThat(ToolTargetSummary.from(null)).isEmpty();
    }

    @Test
    void doesNotLeakFreeTextPromptPayloadsIntoTargetSummary() {
        ToolCall promptCall = call("ask_llm", Map.of("prompt", "What is the secret api key?"));
        assertThat(ToolTargetSummary.from(promptCall)).isEqualTo("ask_llm");
    }

    @Test
    void doesNotLeakUnknownToolContentOrArgumentsIntoTargetSummary() {
        ToolCall contentCall = call("document_store", Map.of("content", "Sensitive document content here..."));
        assertThat(ToolTargetSummary.from(contentCall)).isEqualTo("document_store");

        ToolCall argsCall = call("custom_action", Map.of("args", List.of("--flag1", "--flag2")));
        assertThat(ToolTargetSummary.from(argsCall)).isEqualTo("custom_action");

        ToolCall argumentsCall = call("external_service", Map.of("arguments", "param1=val1"));
        assertThat(ToolTargetSummary.from(argumentsCall)).isEqualTo("external_service");
    }

    @Test
    void preservesFullExecutionArgumentsWithoutRedaction() {
        ToolCall dockerCall =
                call("execution_run", Map.of("command", "docker login --password my_secret_pw --username testuser"));
        assertThat(ToolTargetSummary.from(dockerCall))
                .isEqualTo("docker login --password my_secret_pw --username testuser");

        ToolCall curlCall =
                call("execution_run", Map.of("command", "curl -u admin:my_secret_pass https://api.example.com"));
        assertThat(ToolTargetSummary.from(curlCall)).isEqualTo("curl -u admin:my_secret_pass https://api.example.com");

        ToolCall gitCall =
                call("execution_run", Map.of("command", "git clone https://username:password@github.com/org/repo.git"));
        assertThat(ToolTargetSummary.from(gitCall))
                .isEqualTo("git clone https://username:password@github.com/org/repo.git");
    }

    private static ToolCall call(String toolName, Map<String, Object> values) {
        return new ToolCall(
                new ToolCallId("call-1"),
                new AgentRunId("run-1"),
                new AgentStepId("step-1"),
                new ProviderToolCallCorrelationId("provider-1"),
                new RuntimeIdempotencyKey("idempotency-1"),
                toolName,
                "1.0.0",
                new ToolArguments("schema-" + toolName, "1.0", values),
                NOW);
    }
}

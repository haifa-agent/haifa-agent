package io.haifa.agent.execution.core.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScriptRuntimeResolverTest {
    @Test
    void preparesScriptAsDirectArgvAndBoundedStdinWithoutPuttingSourceOnCommandLine() {
        String source = "Write-Output \"script-body\"";
        var resolver = new ScriptRuntimeResolver(
                ExecutionOperatingSystem.WINDOWS, List.of(ScriptRuntimeResolver.powerShell(Path.of("powershell.exe"))));

        var prepared = resolver.resolve("PowerShell").prepare(source, List.of("first", "it's-safe"));

        assertThat(prepared.command().argv())
                .containsExactly("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", "-")
                .noneMatch(value -> value.contains("script-body"));
        assertThat(new String(prepared.input().bytes(), java.nio.charset.StandardCharsets.UTF_8))
                .contains(
                        "[Console]::InputEncoding = $__haifaUtf8",
                        "[Console]::OutputEncoding = $__haifaUtf8",
                        "$OutputEncoding = $__haifaUtf8",
                        "FromBase64String",
                        "'first' 'it''s-safe'")
                .doesNotContain("script-body")
                .endsWith("\n");
    }

    @Test
    void unsupportedLanguageFailsClosedWithoutFallback() {
        var resolver = new ScriptRuntimeResolver(
                ExecutionOperatingSystem.LINUX, List.of(ScriptRuntimeResolver.bash(Path.of("/bin/bash"))));

        assertThatThrownBy(() -> resolver.resolve("python"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void canonicalDefinitionRequiresApprovalAndDoesNotExposeOperatingSystemSelection() {
        var definition =
                ExecutionToolDefinitionFactory.create("profile@1", true, false, java.util.Set.of("powershell"));

        assertThat(definition.version().value()).isEqualTo("2.0.0");
        assertThat(definition.approvalRequirement()).isEqualTo(io.haifa.agent.tool.api.ToolApprovalRequirement.ALWAYS);
        assertThat(definition.inputSchema().document().toString())
                .contains("mode", "content", "language", "args", "purpose")
                .doesNotContain("operatingSystem", "provider");
        assertThat(definition.outputSchema().document().toString())
                .contains("stdoutSummary", "stderrSummary", "timedOut", "cancelled")
                .doesNotContain("executionId", "contentDigest", "failureDetail");
    }
}

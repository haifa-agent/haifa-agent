package io.haifa.agent.mcp.tool;

import io.haifa.agent.tool.api.ToolAlias;
import io.haifa.agent.tool.api.ToolDefinition;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record McpToolImportCandidate(
        String remoteName,
        String remoteDefinitionDigest,
        boolean enabled,
        Optional<ToolAlias> alias,
        Optional<ToolDefinition> definition,
        Optional<McpToolBindingSnapshot> binding,
        List<McpToolImportDiagnostic> diagnostics) {
    public McpToolImportCandidate {
        remoteName = Objects.requireNonNull(remoteName, "remoteName");
        remoteDefinitionDigest = Objects.requireNonNull(remoteDefinitionDigest, "remoteDefinitionDigest");
        alias = Objects.requireNonNull(alias, "alias");
        definition = Objects.requireNonNull(definition, "definition");
        binding = Objects.requireNonNull(binding, "binding");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        if (enabled && (alias.isEmpty() || definition.isEmpty() || binding.isEmpty() || !diagnostics.isEmpty())) {
            throw new IllegalArgumentException("enabled MCP candidate must be complete and diagnostic-free");
        }
    }
}

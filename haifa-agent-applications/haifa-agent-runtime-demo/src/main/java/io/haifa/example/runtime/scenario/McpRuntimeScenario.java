package io.haifa.example.runtime.scenario;

import io.haifa.agent.tool.core.DefaultToolCatalog;
import io.haifa.example.runtime.mcp.UtilityMcpRuntimePlatform;
import java.net.URI;
import java.util.Optional;
import java.util.Set;

/** Connects one reviewed MCP Tool and contributes it to the ordinary Runtime Tool pipeline. */
public final class McpRuntimeScenario implements RuntimeScenario {
    private final UtilityMcpRuntimePlatform platform;

    private McpRuntimeScenario(UtilityMcpRuntimePlatform platform) {
        this.platform = platform;
    }

    public static McpRuntimeScenario connect(URI endpoint) {
        return new McpRuntimeScenario(UtilityMcpRuntimePlatform.connect(endpoint));
    }

    @Override
    public String id() {
        return "mcp";
    }

    @Override
    public String defaultObjective() {
        return """
               Call utility_unit_convert exactly once with value 1, fromUnit m, toUnit cm,
               scale 2, and roundingMode HALF_UP.
               After receiving the MCP result, reply exactly DEEPSEEK_V4_PRO_MCP_OK.
               """
                .strip();
    }

    @Override
    public String instructions() {
        return """
               The utility_unit_convert MCP tool is available.
               Call it exactly once when requested by the objective.
               After receiving its result, answer directly and do not call it again.
               """;
    }

    @Override
    public Set<String> allowedToolAliases() {
        return Set.of(UtilityMcpRuntimePlatform.LOCAL_TOOL_ALIAS);
    }

    @Override
    public Optional<DefaultToolCatalog> toolCatalog() {
        return Optional.of(platform.catalog());
    }

    @Override
    public void close() {
        platform.close();
    }
}

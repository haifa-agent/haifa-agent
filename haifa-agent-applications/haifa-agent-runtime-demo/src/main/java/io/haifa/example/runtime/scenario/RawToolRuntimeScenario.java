package io.haifa.example.runtime.scenario;

import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.tool.api.SemanticVersion;
import io.haifa.agent.tool.api.ToolAlias;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolDefinition;
import io.haifa.agent.tool.api.ToolExecutionMode;
import io.haifa.agent.tool.api.ToolIdempotency;
import io.haifa.agent.tool.api.ToolName;
import io.haifa.agent.tool.api.ToolProvider;
import io.haifa.agent.tool.api.ToolProviderId;
import io.haifa.agent.tool.api.ToolResourceRequirements;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSchema;
import io.haifa.agent.tool.core.DefaultToolCatalog;
import io.haifa.agent.tool.core.ToolCatalogBuilder;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Low-level Tool SPI example that freezes one raw Tool definition into the Runtime catalog.
 *
 * <p>SDK consumers should normally prefer the typed {@code JavaTool} path shown by
 * {@code haifa-agent-sdk-example}.
 */
public final class RawToolRuntimeScenario implements RuntimeScenario {
    public static final String TOOL_ALIAS = "demo_echo";
    private static final String TOOL_NAME = "demo.echo";

    private final DefaultToolCatalog catalog = createCatalog();

    @Override
    public String id() {
        return "raw-tool";
    }

    @Override
    public String defaultObjective() {
        return """
               Call demo_echo exactly once with text runtime-tool-ok.
               After receiving the tool result, reply exactly DEEPSEEK_V4_PRO_TOOL_OK.
               """
                .strip();
    }

    @Override
    public String instructions() {
        return """
               The demo_echo tool is available.
               Call it only when requested by the objective.
               After receiving its result, answer directly and do not call it again.
               """;
    }

    @Override
    public Set<String> allowedToolAliases() {
        return Set.of(TOOL_ALIAS);
    }

    @Override
    public Optional<DefaultToolCatalog> toolCatalog() {
        return Optional.of(catalog);
    }

    public DefaultToolCatalog catalog() {
        return catalog;
    }

    private static DefaultToolCatalog createCatalog() {
        ToolProviderId providerId = new ToolProviderId("deepseek-runtime-demo");
        ToolProvider provider = new ToolProvider() {
            @Override
            public ToolProviderId id() {
                return providerId;
            }

            @Override
            public ToolResult invoke(io.haifa.agent.tool.api.ToolInvocationRequest request) {
                String text = String.valueOf(request.arguments().values().get("text"));
                return new ToolResult(true, "echoed: " + text, Map.of("text", text), List.of(), List.of(), false);
            }
        };
        Map<String, Object> inputSchema = Map.of(
                "$schema",
                ToolSchema.DRAFT_2020_12,
                "type",
                "object",
                "properties",
                Map.of("text", Map.of("type", "string", "minLength", 1, "maxLength", 128)),
                "required",
                List.of("text"),
                "additionalProperties",
                false);
        Map<String, Object> outputSchema = Map.of(
                "$schema",
                ToolSchema.DRAFT_2020_12,
                "type",
                "object",
                "properties",
                Map.of("text", Map.of("type", "string")),
                "required",
                List.of("text"),
                "additionalProperties",
                false);
        ToolDefinition definition = new ToolDefinition(
                new ToolName(TOOL_NAME),
                new SemanticVersion("1.0.0"),
                providerId,
                "Demo Echo",
                "Returns the supplied text unchanged. Call at most once.",
                new ToolSchema("deepseek-runtime-demo.echo.input", "1.0", inputSchema),
                new ToolSchema("deepseek-runtime-demo.echo.output", "1.0", outputSchema),
                ToolExecutionMode.IN_PROCESS,
                true,
                Duration.ofSeconds(5),
                "single",
                ToolIdempotency.PURE,
                ToolRisk.LOW,
                Set.of(),
                ToolResourceRequirements.none(),
                List.of(),
                ToolApprovalRequirement.NEVER,
                "haifa-agent-runtime-demo",
                false,
                Set.of("example", "runtime-demo"));
        return new ToolCatalogBuilder()
                .register(new ToolAlias(TOOL_ALIAS), definition, "deepseek-runtime-demo.echo.v1", provider)
                .freeze();
    }
}

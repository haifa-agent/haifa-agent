package io.haifa.agent.tool.core;

import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.tool.api.SemanticVersion;
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
import io.haifa.agent.tool.api.ToolSideEffect;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ToolFixtures {
    static final ToolProviderId PROVIDER_ID = new ToolProviderId("project");

    private ToolFixtures() {}

    static ToolDefinition definition() {
        return definition(
                ToolRisk.LOW,
                schema(
                        "file.read.input",
                        Map.of(
                                "$schema",
                                ToolSchema.DRAFT_2020_12,
                                "type",
                                "object",
                                "properties",
                                Map.of("path", Map.of("type", "string", "minLength", 1)),
                                "required",
                                List.of("path"),
                                "additionalProperties",
                                false)));
    }

    static ToolDefinition definition(ToolRisk risk, ToolSchema inputSchema) {
        return new ToolDefinition(
                new ToolName("file.read"),
                new SemanticVersion("1.0.0"),
                PROVIDER_ID,
                "Read file",
                "Reads a workspace file",
                inputSchema,
                schema(
                        "file.read.output",
                        Map.of(
                                "$schema",
                                ToolSchema.DRAFT_2020_12,
                                "type",
                                "object",
                                "properties",
                                Map.of("content", Map.of("type", "string")),
                                "required",
                                List.of("content"))),
                ToolExecutionMode.IN_PROCESS,
                true,
                Duration.ofSeconds(10),
                "per-workspace",
                ToolIdempotency.PURE,
                risk,
                Set.of(ToolSideEffect.FILE_READ),
                new ToolResourceRequirements(Set.of("workspace.read"), Set.of(), Set.of()),
                List.of(),
                ToolApprovalRequirement.NEVER,
                "built-in",
                false,
                Set.of("workspace"));
    }

    static ToolSchema schema(String id, Map<String, Object> document) {
        return new ToolSchema(id, "1.0.0", document);
    }

    static ToolProvider provider() {
        return new ToolProvider() {
            @Override
            public ToolProviderId id() {
                return PROVIDER_ID;
            }

            @Override
            public ToolResult invoke(io.haifa.agent.tool.api.ToolInvocationRequest request) {
                return new ToolResult(true, "read", Map.of("content", "ok"), List.of(), List.of(), false);
            }
        };
    }
}

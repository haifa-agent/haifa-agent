package io.haifa.agent.runtime.core;

import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.SemanticVersion;
import io.haifa.agent.tool.api.ToolAlias;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolDefinition;
import io.haifa.agent.tool.api.ToolExecutionMode;
import io.haifa.agent.tool.api.ToolIdempotency;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolName;
import io.haifa.agent.tool.api.ToolProvider;
import io.haifa.agent.tool.api.ToolProviderId;
import io.haifa.agent.tool.api.ToolResourceRequirements;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSchema;
import io.haifa.agent.tool.api.ToolSideEffect;
import io.haifa.agent.tool.core.DefaultToolInvoker;
import io.haifa.agent.tool.core.JsonSchema202012Validator;
import io.haifa.agent.tool.core.ToolCatalogBuilder;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class TestToolPlatform {
    private static final ToolProviderId PROVIDER_ID = new ToolProviderId("runtime-test");

    private TestToolPlatform() {}

    static RuntimeCoreBuilder install(
            RuntimeCoreBuilder builder,
            String name,
            String version,
            String inputSchemaId,
            boolean sideEffecting,
            ToolHandler handler) {
        return install(
                builder,
                name,
                version,
                inputSchemaId,
                sideEffecting,
                io.haifa.agent.runtime.core.tool.ToolPolicyDecision.ALLOW,
                handler);
    }

    static RuntimeCoreBuilder install(
            RuntimeCoreBuilder builder,
            String name,
            String version,
            String inputSchemaId,
            boolean sideEffecting,
            io.haifa.agent.runtime.core.tool.ToolPolicyDecision decision,
            ToolHandler handler) {
        ToolDefinition definition = definition(name, version, inputSchemaId, sideEffecting);
        ToolProvider provider = new ToolProvider() {
            @Override
            public ToolProviderId id() {
                return PROVIDER_ID;
            }

            @Override
            public ToolResult invoke(ToolInvocationRequest request) {
                return handler.invoke(request);
            }
        };
        var catalog = new ToolCatalogBuilder()
                .register(new ToolAlias(name), definition, "runtime-test", provider)
                .freeze();
        return builder.toolPolicy((run, binding, request) -> decision)
                .toolPlatform(catalog, new DefaultToolInvoker(catalog), new JsonSchema202012Validator());
    }

    static FrozenToolBinding binding(String name, String version, String inputSchemaId, boolean sideEffecting) {
        ToolProvider provider = new ToolProvider() {
            @Override
            public ToolProviderId id() {
                return PROVIDER_ID;
            }

            @Override
            public ToolResult invoke(ToolInvocationRequest request) {
                throw new UnsupportedOperationException();
            }
        };
        return new ToolCatalogBuilder()
                .register(
                        new ToolAlias(name), definition(name, version, inputSchemaId, sideEffecting), "test", provider)
                .freeze()
                .snapshot()
                .bindings()
                .getFirst();
    }

    private static ToolDefinition definition(String name, String version, String inputSchemaId, boolean sideEffecting) {
        Map<String, Object> objectSchema =
                Map.of("$schema", ToolSchema.DRAFT_2020_12, "type", "object", "additionalProperties", true);
        return new ToolDefinition(
                new ToolName(name),
                new SemanticVersion(version),
                PROVIDER_ID,
                name,
                "Runtime test tool " + name,
                new ToolSchema(inputSchemaId, "1.0", objectSchema),
                new ToolSchema(name + ".output", "1.0", objectSchema),
                ToolExecutionMode.IN_PROCESS,
                true,
                Duration.ofSeconds(10),
                "test",
                sideEffecting ? ToolIdempotency.NON_IDEMPOTENT : ToolIdempotency.IDEMPOTENT,
                sideEffecting ? ToolRisk.HIGH : ToolRisk.LOW,
                sideEffecting ? Set.of(ToolSideEffect.FILE_WRITE) : Set.of(ToolSideEffect.FILE_READ),
                ToolResourceRequirements.none(),
                List.of(),
                ToolApprovalRequirement.NEVER,
                "test",
                false,
                Set.of("test"));
    }

    @FunctionalInterface
    interface ToolHandler {
        ToolResult invoke(ToolInvocationRequest request);
    }
}

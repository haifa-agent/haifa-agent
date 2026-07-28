package io.haifa.agent.personalassistant.application.tool;

import io.haifa.agent.core.tool.ToolResult;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure deterministic checklist normalization Tool used by the Personal product and offline E2E. */
public final class PersonalChecklistTool implements ToolProvider {
    public static final ToolAlias ALIAS = new ToolAlias("personal_checklist");
    public static final ToolProviderId PROVIDER_ID = new ToolProviderId("haifa-personal-tools");

    @Override
    public ToolProviderId id() {
        return PROVIDER_ID;
    }

    @Override
    public ToolResult invoke(ToolInvocationRequest request) {
        request.cancellation().throwIfCancellationRequested();
        Object raw = request.arguments().values().get("items");
        if (!(raw instanceof List<?> values) || values.isEmpty() || values.size() > 8) {
            throw new IllegalArgumentException("items must contain between one and eight entries");
        }
        List<String> items = new ArrayList<>();
        for (Object value : values) {
            String item = String.valueOf(value).trim();
            if (item.isEmpty() || item.length() > 256) {
                throw new IllegalArgumentException("each checklist item must contain 1 to 256 characters");
            }
            items.add(item);
        }
        return new ToolResult(
                true,
                "Prepared " + items.size() + " checklist items",
                Map.of("items", List.copyOf(items), "count", items.size()),
                List.of(),
                List.of(),
                false);
    }

    public static ToolDefinition definition() {
        Map<String, Object> input = Map.of(
                "$schema",
                ToolSchema.DRAFT_2020_12,
                "type",
                "object",
                "additionalProperties",
                false,
                "properties",
                Map.of(
                        "items",
                        Map.of(
                                "type",
                                "array",
                                "minItems",
                                1,
                                "maxItems",
                                8,
                                "items",
                                Map.of("type", "string", "minLength", 1, "maxLength", 256))),
                "required",
                List.of("items"));
        Map<String, Object> output = Map.of(
                "$schema",
                ToolSchema.DRAFT_2020_12,
                "type",
                "object",
                "additionalProperties",
                false,
                "properties",
                Map.of(
                        "items", Map.of("type", "array", "maxItems", 8, "items", Map.of("type", "string")),
                        "count", Map.of("type", "integer", "minimum", 1, "maximum", 8)),
                "required",
                List.of("items", "count"));
        return new ToolDefinition(
                new ToolName("personal.checklist"),
                new SemanticVersion("1.0.0"),
                PROVIDER_ID,
                "Prepare checklist",
                "Normalize one to eight personal checklist items without external side effects.",
                new ToolSchema("haifa.personal.checklist.input", "1.0.0", input),
                new ToolSchema("haifa.personal.checklist.output", "1.0.0", output),
                ToolExecutionMode.IN_PROCESS,
                true,
                Duration.ofSeconds(5),
                "per-run-read",
                ToolIdempotency.PURE,
                ToolRisk.LOW,
                Set.<ToolSideEffect>of(),
                ToolResourceRequirements.none(),
                List.of(),
                ToolApprovalRequirement.NEVER,
                "haifa-personal-assistant",
                false,
                Set.of("personal", "planning"));
    }
}

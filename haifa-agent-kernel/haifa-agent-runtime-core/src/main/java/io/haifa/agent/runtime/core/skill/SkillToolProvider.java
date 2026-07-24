package io.haifa.agent.runtime.core.skill;

import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.skill.api.SkillActivationRequest;
import io.haifa.agent.skill.api.SkillAlias;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class SkillToolProvider implements ToolProvider {
    public static final ToolProviderId PROVIDER_ID = new ToolProviderId("haifa-runtime-skill");
    public static final ToolAlias LOAD_ALIAS = new ToolAlias("skill_load");
    public static final ToolAlias RESOURCE_READ_ALIAS = new ToolAlias("skill_resource_read");

    private final SkillActivationService skills;

    public SkillToolProvider(SkillActivationService skills) {
        this.skills = Objects.requireNonNull(skills);
    }

    public List<SkillToolCatalogContribution> contributions() {
        return List.of(
                new SkillToolCatalogContribution(LOAD_ALIAS, loadDefinition(), "runtime-skill-load", this),
                new SkillToolCatalogContribution(
                        RESOURCE_READ_ALIAS, resourceReadDefinition(), "runtime-skill-resource-read", this));
    }

    @Override
    public ToolProviderId id() {
        return PROVIDER_ID;
    }

    @Override
    public ToolResult invoke(ToolInvocationRequest request) {
        String alias = requiredText(request, "skill");
        String reason = optionalText(request, "reason", "model requested Skill activation");
        SkillActivationRequest activationRequest = new SkillActivationRequest(
                request.runId(),
                request.tenant(),
                request.principal(),
                new SkillAlias(alias),
                reason,
                request.toolCallId().value());
        request.observer().dispatched();
        ToolResult result;
        switch (request.binding().definition().name().value()) {
            case "skill.load" -> {
                var activation = skills.activate(activationRequest);
                result = result(
                        "Activated Skill " + activation.binding().alias().value(),
                        Map.of(
                                "skill", activation.binding().alias().value(),
                                "digest",
                                        activation
                                                .binding()
                                                .coordinate()
                                                .contentDigest()
                                                .value(),
                                "activated", true,
                                "instructionBytes", activation.instructionBytes(),
                                "estimatedTokens", activation.estimatedTokens()));
            }
            case "skill.resource.read" -> {
                String path = requiredText(request, "path");
                SkillResourceRead resource = skills.readResource(activationRequest, path);
                result = result(
                        "Read activated Skill resource",
                        Map.of(
                                "skill", alias,
                                "source",
                                        resource.binding().coordinate().source().externalForm(),
                                "path", resource.resource().relativePath(),
                                "digest", resource.resource().digest().value(),
                                "mediaType", resource.resource().mediaType(),
                                "securityLabel", "untrusted-skill-resource",
                                "content", resource.content()));
            }
            default -> throw new IllegalArgumentException("unsupported Skill tool");
        }
        request.observer().acknowledged();
        return result;
    }

    private static ToolResult result(String summary, Map<String, Object> data) {
        return new ToolResult(true, summary, data, List.of(), List.of(), false);
    }

    private static String requiredText(ToolInvocationRequest request, String name) {
        Object value = request.arguments().values().get(name);
        if (!(value instanceof String text) || text.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must be non-blank text");
        }
        return text.trim();
    }

    private static String optionalText(ToolInvocationRequest request, String name, String fallback) {
        Object value = request.arguments().values().get(name);
        if (value == null) return fallback;
        if (!(value instanceof String text) || text.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must be non-blank text");
        }
        return text.trim();
    }

    private static ToolDefinition loadDefinition() {
        return definition(
                "skill.load",
                "Activate Skill",
                "Activate one Skill allowed by the frozen run configuration for the next context build.",
                schema(
                        "haifa.skill.load.input",
                        Map.of(
                                "skill", Map.of("type", "string"),
                                "reason", Map.of("type", "string", "maxLength", 512)),
                        List.of("skill")),
                schema(
                        "haifa.skill.load.output",
                        Map.of(
                                "skill", Map.of("type", "string"),
                                "digest", Map.of("type", "string"),
                                "activated", Map.of("type", "boolean"),
                                "instructionBytes", Map.of("type", "integer"),
                                "estimatedTokens", Map.of("type", "integer")),
                        List.of("skill", "digest", "activated", "instructionBytes", "estimatedTokens")));
    }

    private static ToolDefinition resourceReadDefinition() {
        return definition(
                "skill.resource.read",
                "Read Skill resource",
                "Read an indexed text resource from a Skill that is already activated for this run.",
                schema(
                        "haifa.skill.resource.read.input",
                        Map.of(
                                "skill", Map.of("type", "string"),
                                "path", Map.of("type", "string"),
                                "reason", Map.of("type", "string", "maxLength", 512)),
                        List.of("skill", "path")),
                schema(
                        "haifa.skill.resource.read.output",
                        Map.of(
                                "skill", Map.of("type", "string"),
                                "source", Map.of("type", "string"),
                                "path", Map.of("type", "string"),
                                "digest", Map.of("type", "string"),
                                "mediaType", Map.of("type", "string"),
                                "securityLabel", Map.of("type", "string"),
                                "content", Map.of("type", "string")),
                        List.of("skill", "source", "path", "digest", "mediaType", "securityLabel", "content")));
    }

    private static ToolDefinition definition(
            String name, String title, String description, ToolSchema input, ToolSchema output) {
        return new ToolDefinition(
                new ToolName(name),
                new SemanticVersion("1.0.0"),
                PROVIDER_ID,
                title,
                description,
                input,
                output,
                ToolExecutionMode.IN_PROCESS,
                true,
                Duration.ofSeconds(10),
                "per-run-skill",
                ToolIdempotency.IDEMPOTENT,
                ToolRisk.LOW,
                Set.of(),
                ToolResourceRequirements.none(),
                List.of(),
                ToolApprovalRequirement.NEVER,
                "haifa-runtime-core",
                false,
                Set.of("skill", "progressive-disclosure"));
    }

    private static ToolSchema schema(String id, Map<String, Object> properties, List<String> required) {
        return new ToolSchema(
                id,
                "1.0.0",
                Map.of(
                        "$schema",
                        "https://json-schema.org/draft/2020-12/schema",
                        "type",
                        "object",
                        "additionalProperties",
                        false,
                        "properties",
                        properties,
                        "required",
                        required));
    }
}

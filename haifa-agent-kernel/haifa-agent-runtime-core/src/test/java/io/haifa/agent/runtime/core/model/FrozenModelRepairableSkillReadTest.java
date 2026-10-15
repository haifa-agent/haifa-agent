package io.haifa.agent.runtime.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.runtime.core.skill.SkillToolProvider;
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
import io.haifa.agent.tool.core.ToolCatalogBuilder;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FrozenModelRepairableSkillReadTest {
    private static final ModelToolSpecification READER = specification("skill_resource_read");
    private static final ModelToolSpecification LOADER = specification("skill_load");

    @Test
    void selectsAHiddenBuiltInReaderOwnedByTheFrozenBinding() {
        var frozen = List.of(binding("skill_resource_read", SkillToolProvider.PROVIDER_ID));

        assertThat(FrozenModelInvoker.repairableSkillReadTools(frozen, List.of(READER, LOADER), List.of(LOADER)))
                .containsExactly(READER);
    }

    @Test
    void ignoresACustomProviderSharingTheReaderAlias() {
        var frozen = List.of(binding("skill_resource_read", new ToolProviderId("host-custom")));

        assertThat(FrozenModelInvoker.repairableSkillReadTools(frozen, List.of(READER, LOADER), List.of(LOADER)))
                .isEmpty();
    }

    @Test
    void doesNotRepairWhenNoToolsAreDisclosed() {
        var frozen = List.of(binding("skill_resource_read", SkillToolProvider.PROVIDER_ID));

        assertThat(FrozenModelInvoker.repairableSkillReadTools(frozen, List.of(READER, LOADER), List.of()))
                .isEmpty();
    }

    @Test
    void doesNotDuplicateAnAlreadyDisclosedReader() {
        var frozen = List.of(binding("skill_resource_read", SkillToolProvider.PROVIDER_ID));

        assertThat(FrozenModelInvoker.repairableSkillReadTools(
                        frozen, List.of(READER, LOADER), List.of(LOADER, READER)))
                .isEmpty();
    }

    @Test
    void ignoresFrozenToolsThatAreNotTheReaderAlias() {
        var frozen = List.of(binding("skill_load", SkillToolProvider.PROVIDER_ID));

        assertThat(FrozenModelInvoker.repairableSkillReadTools(frozen, List.of(LOADER), List.of(LOADER)))
                .isEmpty();
    }

    private static FrozenToolBinding binding(String name, ToolProviderId providerId) {
        ToolProvider provider = new ToolProvider() {
            @Override
            public ToolProviderId id() {
                return providerId;
            }

            @Override
            public ToolResult invoke(ToolInvocationRequest request) {
                throw new UnsupportedOperationException();
            }
        };
        return new ToolCatalogBuilder()
                .register(new ToolAlias(name), definition(name, providerId), "runtime-test", provider)
                .freeze()
                .snapshot()
                .bindings()
                .getFirst();
    }

    private static ToolDefinition definition(String name, ToolProviderId providerId) {
        Map<String, Object> objectSchema =
                Map.of("$schema", ToolSchema.DRAFT_2020_12, "type", "object", "additionalProperties", true);
        return new ToolDefinition(
                new ToolName(name),
                new SemanticVersion("1.0.0"),
                providerId,
                name,
                "Skill tool " + name,
                new ToolSchema(name + ".input", "1.0", objectSchema),
                new ToolSchema(name + ".output", "1.0", objectSchema),
                ToolExecutionMode.IN_PROCESS,
                true,
                Duration.ofSeconds(10),
                "test",
                ToolIdempotency.IDEMPOTENT,
                ToolRisk.LOW,
                Set.of(),
                ToolResourceRequirements.none(),
                List.of(),
                ToolApprovalRequirement.NEVER,
                "runtime-core-test",
                false,
                Set.of("skill"));
    }

    private static ModelToolSpecification specification(String name) {
        return new ModelToolSpecification(
                name, "1.0.0", name, name + ".input", "1.0.0", Map.of("type", "object"), false);
    }
}

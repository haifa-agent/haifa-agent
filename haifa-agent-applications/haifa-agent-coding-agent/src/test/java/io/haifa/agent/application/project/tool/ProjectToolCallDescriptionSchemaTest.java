package io.haifa.agent.application.project.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.sandbox.api.SandboxConfigurationDigest;
import io.haifa.agent.sandbox.api.SandboxProfile;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolProvider;
import io.haifa.agent.tool.api.ToolProviderId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProjectToolCallDescriptionSchemaTest {

    @Test
    void disclosesOptionalCallDescriptionOnEveryBuiltInProjectTool() {
        var catalog = new ProjectToolCatalog();
        SandboxProfile profile = new SandboxProfile(
                new SandboxProfileRef("cli-host-guarded", "1"),
                "host-guarded",
                SandboxConfigurationDigest.sha256Fields(List.of("schema-test")),
                Set.of(),
                Set.of(),
                true);
        var frozen = catalog.freeze(
                catalog.names(), Set.of("file_read", "file_write", "execution_run"), true, provider(), profile);

        assertThat(frozen.snapshot().bindings()).hasSize(catalog.names().size());
        assertThat(frozen.snapshot().bindings()).allSatisfy(binding -> {
            @SuppressWarnings("unchecked")
            var properties = (Map<String, Object>)
                    binding.definition().inputSchema().document().get("properties");
            @SuppressWarnings("unchecked")
            var required =
                    (List<String>) binding.definition().inputSchema().document().get("required");
            assertThat(properties).containsKey("description");
            @SuppressWarnings("unchecked")
            var description = (Map<String, Object>) properties.get("description");
            assertThat(description)
                    .containsEntry("type", "string")
                    .containsEntry("maxLength", 256)
                    .doesNotContainKey("minLength");
            assertThat(description.get("description").toString())
                    .contains("user-facing explanation", "intent, not a completed outcome");
            assertThat(required).doesNotContain("description");
        });
    }

    private static ToolProvider provider() {
        return new ToolProvider() {
            @Override
            public ToolProviderId id() {
                return ProjectToolExecutor.PROVIDER_ID;
            }

            @Override
            public io.haifa.agent.core.tool.ToolResult invoke(ToolInvocationRequest request) {
                throw new UnsupportedOperationException("catalog assembly must not execute");
            }
        };
    }
}

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
        var frozen = catalog.freeze(
                catalog.names(), Set.of("file_read", "file_write", "execution_run"), true, provider(), profile());

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

    @Test
    void usesShortDisplayTitlesWithoutShorteningModelVisibleDescriptions() {
        var catalog = new ProjectToolCatalog();
        var frozen = catalog.freeze(
                catalog.names(), Set.of("file_read", "file_write", "execution_run"), true, provider(), profile());
        Map<String, String> titles = frozen.snapshot().bindings().stream()
                .collect(java.util.stream.Collectors.toMap(
                        binding -> binding.definition().name().value(),
                        binding -> binding.definition().title()));
        Map<String, String> descriptions = frozen.snapshot().bindings().stream()
                .collect(java.util.stream.Collectors.toMap(
                        binding -> binding.definition().name().value(),
                        binding -> binding.definition().description()));

        assertThat(titles)
                .containsEntry("file_read", "Read File")
                .containsEntry("file_write", "Write File")
                .containsEntry("file_create", "Create File")
                .containsEntry("file_patch", "Apply Patch")
                .containsEntry("file_delete", "Delete File")
                .containsEntry("file_move", "Move File")
                .containsEntry("file_list", "List Files")
                .containsEntry("file_stat", "Inspect Path")
                .containsEntry("file_search", "Search Files")
                .containsEntry("file_diff", "Preview Diff")
                .containsEntry("execution_run", "Run Command")
                .containsEntry("workspace_attach", "Add Directory");

        assertThat(descriptions.get("file_list")).startsWith("List workspace files within");
        assertThat(descriptions.get("file_stat")).startsWith("Inspect workspace path within");
        assertThat(descriptions.get("file_search")).startsWith("Search workspace files within");
        assertThat(descriptions.get("file_diff")).startsWith("Preview file diff within");
        assertThat(descriptions.get("file_delete")).startsWith("Delete workspace path within");
        assertThat(descriptions.get("file_move")).startsWith("Move workspace path within");
    }

    private static SandboxProfile profile() {
        return new SandboxProfile(
                new SandboxProfileRef("cli-host-guarded", "1"),
                "host-guarded",
                SandboxConfigurationDigest.sha256Fields(List.of("schema-test")),
                Set.of(),
                Set.of(),
                true);
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

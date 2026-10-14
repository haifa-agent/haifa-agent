package io.haifa.agent.starter;

import static io.haifa.agent.starter.SkillScriptTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import io.haifa.agent.execution.core.tool.ExecutionToolDefinitionFactory;
import io.haifa.agent.sdk.api.HaifaAgentException;
import io.haifa.agent.sdk.contribution.ToolRegistration;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolDefinition;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Starter-specific wiring; byte/identity matrices and publication races live at their owning layers. */
class SkillScriptPlatformsTest {
    @Test
    void executesTheEntryFileKeepsOutputsInPlaceAndReusesTheVersionDirectory(@TempDir Path temporary) throws Exception {
        assumeThat(runtimeExecutable()).matches(path -> path.toFile().isFile());
        Path workspace = createWorkspace(temporary, "workspace");
        Path dataRoot = createWorkspace(temporary, "data");
        Files.createDirectories(workspace.resolve("outputs"));
        Files.writeString(workspace.resolve("outputs").resolve("result.txt"), STALE, StandardCharsets.UTF_8);
        SkillPackageLoader loader = new SkillPackageLoader(packageFiles(entryScript(false)));
        SkillScriptPlatforms.SkillScriptToolDeclaration declaration = declaration(loader.frozen);
        var spec = spec(workspace, dataRoot, loader, List.of(declaration));

        try (SkillScriptPlatforms.SkillScriptPlatform platform =
                SkillScriptPlatforms.trusted(spec, TENANT, PRINCIPAL)) {
            assertThat(platform.toolNames()).containsExactly("generate_note");
            ToolRegistration registration = platform.registrations().get(0);
            ToolDefinition definition = registration.definition();
            assertThat(definition.approvalRequirement()).isEqualTo(ToolApprovalRequirement.ALWAYS);
            // R1: the single declared result contract is the canonical execution result schema.
            assertThat(definition.outputSchema().document())
                    .isEqualTo(ExecutionToolDefinitionFactory.executionResultSchema()
                            .document());

            FrozenToolBinding binding = freeze(registration);
            var first = registration.provider().invoke(invocation(binding, definition, "invoke-1"));

            assertThat(first.successful()).isTrue();
            assertThat(first.structuredData()).containsEntry("processState", "EXITED");
            // The product outputs/ chain owns publication: the script's file stays exactly where
            // that chain reads it and prior history is never deleted by this entry.
            Path output = workspace.resolve("outputs").resolve("result.txt");
            assertThat(Files.readString(output, StandardCharsets.UTF_8)).isEqualTo(EXPECTED_OUTPUT);

            // A second invocation of the same Skill version reuses the prepared directory and
            // never re-reads the frozen package bytes through the content loader.
            int loadsBefore = loader.loads.get();
            assertThat(loadsBefore).isGreaterThan(0);
            var second = registration.provider().invoke(invocation(binding, definition, "invoke-2"));
            assertThat(second.successful()).isTrue();
            assertThat(loader.loads.get()).isEqualTo(loadsBefore);
            assertThat(Files.readString(output, StandardCharsets.UTF_8))
                    .contains("stale prefix")
                    .endsWith(MARKER + "\n");
            // The prepared version directory persists for the next invocation and the next Run.
            try (var entries = Files.list(workspace.resolve(SkillScriptPackageDirectory.DIRECTORY))) {
                assertThat(entries.findFirst()).isPresent();
            }
        }
    }

    @Test
    void scriptFailureNeverDeletesHistoricalOutputs(@TempDir Path temporary) throws Exception {
        assumeThat(runtimeExecutable()).matches(path -> path.toFile().isFile());
        Path workspace = createWorkspace(temporary, "workspace");
        Path dataRoot = createWorkspace(temporary, "data");
        Files.createDirectories(workspace.resolve("outputs"));
        Path historical = workspace.resolve("outputs").resolve("result.txt");
        Files.writeString(historical, STALE, StandardCharsets.UTF_8);

        SkillPackageLoader loader = new SkillPackageLoader(packageFiles(entryScript(true)));
        var spec = spec(workspace, dataRoot, loader, List.of(slowDeclaration()));

        try (SkillScriptPlatforms.SkillScriptPlatform platform =
                SkillScriptPlatforms.trusted(spec, TENANT, PRINCIPAL)) {
            var registration = platform.registrations().get(0);
            FrozenToolBinding binding = freeze(registration);
            var result = registration
                    .provider()
                    .invoke(invocation(binding, registration.definition(), "timed-out-invoke-1"));

            assertThat(result.successful()).isFalse();
            assertThat(result.structuredData()).containsEntry("processState", "TIMED_OUT");
            // R4: this entry adds no output capture or deletion - history is untouched.
            assertThat(Files.readString(historical, StandardCharsets.UTF_8)).isEqualTo(STALE);
        }
    }

    @Test
    void rejectsRuntimeExecutablesThatAreNotExplicitExistingFiles(@TempDir Path temporary) {
        var forbidden = new SkillScriptPlatforms.SkillScriptRuntime("bun", Path.of("bun"));
        assertThatThrownBy(forbidden::preflight)
                .isInstanceOf(HaifaAgentException.class)
                .hasFieldOrPropertyWithValue("code", "SKILL_SCRIPT_RUNTIME_UNAVAILABLE")
                .hasMessageContaining("explicit path");
        var missing = new SkillScriptPlatforms.SkillScriptRuntime(
                "bun", temporary.resolve("does-not-exist").resolve("bun.exe"));
        assertThatThrownBy(missing::preflight)
                .isInstanceOf(HaifaAgentException.class)
                .hasFieldOrPropertyWithValue("code", "SKILL_SCRIPT_RUNTIME_UNAVAILABLE");
    }

    @Test
    void wiresTheExecutionDeclarationIntoTheToolIdentity() {
        var binding = loaderBinding(packageFiles(entryScript(false)));
        var declared = declaration(binding);
        String execution = "d".repeat(64);
        String sandbox = "sha256:" + "a".repeat(64);
        var definition = declared.toDefinition(execution, sandbox);
        assertThat(definition.resources().executionProfiles())
                .containsExactlyInAnyOrder(
                        execution,
                        "sandbox@" + sandbox,
                        io.haifa.agent.execution.core.tool.TrustedSkillScriptToolSpec.skillProfileReference(
                                binding,
                                declared.scriptRelativePath(),
                                declared.scriptDigest(),
                                declared.scriptRuntimeRef(),
                                declared.timeout(),
                                declared.purpose()));
    }

    @Test
    void rejectsAnUndeclaredRuntimeBeforeRegisteringTools(@TempDir Path temporary) throws Exception {
        Path workspace = createWorkspace(temporary, "workspace");
        Path dataRoot = createWorkspace(temporary, "data");
        var loader = new SkillPackageLoader(packageFiles(entryScript(false)));
        var undeclared = declaration(loader.frozen, "bun", Duration.ofSeconds(60), "undeclared runtime fixture");
        assertThatThrownBy(() -> SkillScriptPlatforms.trusted(
                        spec(workspace, dataRoot, loader, List.of(undeclared)), TENANT, PRINCIPAL))
                .isInstanceOf(HaifaAgentException.class)
                .hasFieldOrPropertyWithValue("code", "SKILL_SCRIPT_RUNTIME_UNAVAILABLE")
                .hasMessageContaining("undeclared runtime");
    }
}

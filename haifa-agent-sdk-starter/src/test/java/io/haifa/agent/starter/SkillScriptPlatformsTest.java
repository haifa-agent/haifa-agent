package io.haifa.agent.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import io.haifa.agent.artifact.Artifact;
import io.haifa.agent.artifact.ArtifactService;
import io.haifa.agent.artifact.InMemoryArtifactPayloadStore;
import io.haifa.agent.artifact.InMemoryArtifactStore;
import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.id.UuidV7IdentifierGenerator;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.execution.core.tool.TrustedScriptArguments;
import io.haifa.agent.sdk.api.HaifaAgentException;
import io.haifa.agent.sdk.contribution.ToolRegistration;
import io.haifa.agent.skill.api.FrozenSkillBinding;
import io.haifa.agent.skill.api.SkillAlias;
import io.haifa.agent.skill.api.SkillContent;
import io.haifa.agent.skill.api.SkillContentDigest;
import io.haifa.agent.skill.api.SkillContentLoader;
import io.haifa.agent.skill.api.SkillCoordinate;
import io.haifa.agent.skill.api.SkillDeclaredVersion;
import io.haifa.agent.skill.api.SkillMetadata;
import io.haifa.agent.skill.api.SkillName;
import io.haifa.agent.skill.api.SkillPackageIndex;
import io.haifa.agent.skill.api.SkillResourceKind;
import io.haifa.agent.skill.api.SkillResourceRef;
import io.haifa.agent.skill.api.SkillScopeRef;
import io.haifa.agent.skill.api.SkillSourceRef;
import io.haifa.agent.skill.api.SkillTrustDigests;
import io.haifa.agent.skill.api.SkillVisibilityContext;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolDefinition;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolName;
import io.haifa.agent.tool.api.ToolSchema;
import io.haifa.agent.tool.core.ToolCatalogBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Entry-file trusted Skill script execution end to end through the real sandbox: materialization,
 * relative entry-file resolution, declared outputs and artifact publication, run isolation and
 * fail-closed runtime preflight.
 */
class SkillScriptPlatformsTest {
    private static final TenantRef TENANT = new TenantRef("public");
    private static final PrincipalRef PRINCIPAL = new PrincipalRef("default-public-user", "user");
    private static final ProjectRef PROJECT = new ProjectRef("skill-script-entry-tests");
    private static final Duration MAXIMUM_TIMEOUT = Duration.ofMinutes(10);
    private static final String MARKER = "helper marker v1";
    private static final String STALE = "stale junk";

    @Test
    void executesTheEntryFileCapturesDeclaredOutputsAndPublishesTheirArtifacts(@TempDir Path temporary)
            throws Exception {
        assumeThat(runtimeExecutable()).matches(path -> path.toFile().isFile());
        Path workspace = createWorkspace(temporary, "workspace");
        Path dataRoot = createWorkspace(temporary, "data");
        byte[] entry = entryScript(false);
        ArtifactService artifacts = artifacts();
        SkillScriptPlatforms.SkillScriptToolDeclaration declaration = declaration(entry);
        var spec = platformSpec(workspace, dataRoot, packageFiles(entry), List.of(declaration), artifacts);

        try (SkillScriptPlatforms.SkillScriptPlatform platform =
                SkillScriptPlatforms.trusted(spec, TENANT, PRINCIPAL)) {
            assertThat(platform.toolNames()).containsExactly("generate_note");
            ToolRegistration registration = platform.registrations().get(0);
            ToolDefinition definition = registration.definition();
            assertThat(definition.approvalRequirement()).isEqualTo(ToolApprovalRequirement.ALWAYS);

            FrozenToolBinding binding = freeze(registration);
            var first = registration.provider().invoke(invocation(binding, definition, "invoke-1"));

            assertThat(first.successful()).isTrue();
            assertThat(first.structuredData()).containsEntry("processState", "EXITED");
            List<Map<String, Object>> outputs = outputs(first);
            assertThat(outputs).singleElement().satisfies(output -> {
                assertThat(output.get("name")).isEqualTo("result.txt");
                assertThat(output.get("artifactType")).isEqualTo(SkillScriptOutputsPublisher.ARTIFACT_TYPE);
                assertThat(output.get("mediaType")).isEqualTo("text/plain");
                assertThat(output.get("byteCount")).isEqualTo((MARKER + "\n").length());
            });
            Artifact firstArtifact = findArtifact(artifacts, output(outputs, "artifactId"));
            // The process resolved the entry-file-relative helper, proving entry-file identity that
            // the standard-input model cannot express.
            byte[] firstBytes = artifacts.load(firstArtifact);
            assertThat(new String(firstBytes, StandardCharsets.UTF_8))
                    .isEqualTo(MARKER + "\n")
                    .doesNotContain(STALE);
            assertThat(firstArtifact.provenance().sourceHash()).isEqualTo(output(outputs, "sha256"));
            assertThat(firstArtifact.provenance().project()).isEqualTo(PROJECT);
            assertThat(firstArtifact.provenance().workspaceRef()).startsWith("trusted-skill-script:v1:");
            assertThat(firstArtifact.provenance().sourceLogicalPath()).isEqualTo("outputs/result.txt");
            assertThat(firstArtifact.provenance().exportPolicy()).isEqualTo(SkillScriptOutputsPublisher.EXPORT_POLICY);

            // The captured file was removed after publication: one Run's outputs never leak into
            // the Workspace for the next caller.
            assertThat(java.nio.file.Files.exists(workspace.resolve("outputs").resolve("result.txt")))
                    .isFalse();

            // A7: a stale leftover file is cleared before the next invocation and never becomes
            // the new run's artifact.
            java.nio.file.Files.createDirectories(workspace.resolve("outputs"));
            java.nio.file.Files.writeString(
                    workspace.resolve("outputs").resolve("result.txt"), STALE, StandardCharsets.UTF_8);
            var second = registration.provider().invoke(invocation(binding, definition, "invoke-2"));
            assertThat(second.successful()).isTrue();
            byte[] secondBytes = artifacts.load(findArtifact(artifacts, output(outputs(second), "artifactId")));
            assertThat(new String(secondBytes, StandardCharsets.UTF_8))
                    .isEqualTo(MARKER + "\n")
                    .doesNotContain(STALE);
        }
    }

    @Test
    void timesOutFailsClosedAndCleansTheMaterializedPackage(@TempDir Path temporary) throws Exception {
        assumeThat(runtimeExecutable()).matches(path -> path.toFile().isFile());
        Path workspace = createWorkspace(temporary, "workspace");
        Path dataRoot = createWorkspace(temporary, "data");
        byte[] entry = entryScript(false);
        ArtifactService artifacts = artifacts();
        var slowEntry = entryScript(true);
        var spec = platformSpec(workspace, dataRoot, packageFiles(slowEntry), List.of(slowDeclaration()), artifacts);

        try (SkillScriptPlatforms.SkillScriptPlatform platform =
                SkillScriptPlatforms.trusted(spec, TENANT, PRINCIPAL)) {
            ToolRegistration registration = platform.registrations().get(0);
            FrozenToolBinding binding = freeze(registration);
            var result = registration
                    .provider()
                    .invoke(invocation(binding, registration.definition(), "timed-out-invoke-1"));

            assertThat(result.successful()).isFalse();
            assertThat(result.structuredData()).containsEntry("processState", "TIMED_OUT");
            assertThat(artifacts.findByProject(PROJECT.projectId())).isEmpty();
            assertThat(java.nio.file.Files.exists(workspace.resolve(".haifa-trusted-skills")))
                    .isFalse();
        }
    }

    @Test
    void failsClosedWhenTheFrozenPackageDriftsOrTheRuntimeIsNotDeclared(@TempDir Path temporary) throws Exception {
        Path workspace = createWorkspace(temporary, "workspace");
        Path dataRoot = createWorkspace(temporary, "data");
        byte[] entry = entryScript(false);
        ArtifactService artifacts = artifacts();
        Map<String, byte[]> drifted = packageFiles(entry);
        drifted.put("scripts/helpers/note.txt", bytes("drifted bytes for the rejection path"));

        var drifting = platformSpec(workspace, dataRoot, drifted, List.of(declaration(entry)), artifacts);
        try (SkillScriptPlatforms.SkillScriptPlatform platform =
                SkillScriptPlatforms.trusted(drifting, TENANT, PRINCIPAL)) {
            ToolRegistration registration = platform.registrations().get(0);
            FrozenToolBinding binding = freeze(registration);
            assertThatThrownBy(() -> registration
                            .provider()
                            .invoke(invocation(binding, registration.definition(), "drifted-invoke-1")))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("package content has drifted");
            assertThat(artifacts.findByProject(PROJECT.projectId())).isEmpty();
            assertThat(java.nio.file.Files.exists(workspace.resolve(".haifa-trusted-skills")))
                    .isFalse();
        }

        // The Tool's declared runtime is not among the platform runtimes: fail closed with the
        // stable code before any Tool is registered on a builder.
        var undeclared = platformSpec(workspace, dataRoot, packageFiles(entry), List.of(declaration(entry)), artifacts);
        var wrongRuntime = new SkillScriptPlatforms.SkillScriptRuntime("python", runtimeExecutable());
        var rebuilt = new SkillScriptPlatforms.SkillScriptSpec(
                PROJECT,
                workspace,
                dataRoot,
                List.of(wrongRuntime),
                loader(packageFiles(entry)),
                List.of(declaration(entry)),
                artifacts,
                Duration.ofSeconds(90),
                MAXIMUM_TIMEOUT,
                65_536,
                1_000);
        assertThatThrownBy(() -> SkillScriptPlatforms.trusted(rebuilt, TENANT, PRINCIPAL))
                .isInstanceOf(HaifaAgentException.class)
                .hasFieldOrPropertyWithValue("code", "SKILL_SCRIPT_RUNTIME_UNAVAILABLE");
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
        assertThatThrownBy(() -> new SkillScriptPlatforms.SkillScriptOutput("../escape", "text/plain"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plain file names");
    }

    private static SkillScriptPlatforms.SkillScriptSpec platformSpec(
            Path workspace,
            Path dataRoot,
            Map<String, byte[]> files,
            List<SkillScriptPlatforms.SkillScriptToolDeclaration> declarations,
            ArtifactService artifacts) {
        return new SkillScriptPlatforms.SkillScriptSpec(
                PROJECT,
                workspace,
                dataRoot,
                List.of(new SkillScriptPlatforms.SkillScriptRuntime(runtimeLanguage(), runtimeExecutable())),
                loader(files),
                declarations,
                artifacts,
                Duration.ofSeconds(90),
                MAXIMUM_TIMEOUT,
                65_536,
                1_000);
    }

    private static SkillScriptPlatforms.SkillScriptToolDeclaration declaration(byte[] entry) {
        return new SkillScriptPlatforms.SkillScriptToolDeclaration(
                new ToolName("generate_note"),
                "Generate one bounded note",
                "Generates one bounded note artifact through the frozen skill package script.",
                new ToolSchema(
                        "haifa.skillscript.note.input",
                        "1.0.0",
                        Map.of(
                                "$schema",
                                ToolSchema.DRAFT_2020_12,
                                "type",
                                "object",
                                "additionalProperties",
                                false,
                                "properties",
                                Map.of())),
                new ToolSchema(
                        "haifa.skillscript.note.output",
                        "1.0.0",
                        Map.of("$schema", ToolSchema.DRAFT_2020_12, "type", "object", "additionalProperties", false)),
                Duration.ofMinutes(2),
                binding(packageFiles(entry)),
                scriptRelative(),
                runtimeLanguage(),
                Duration.ofSeconds(60),
                "verify the trusted package entry run",
                arguments -> TrustedScriptArguments.atWorkspaceRoot(List.of()),
                List.of(new SkillScriptPlatforms.SkillScriptOutput("result.txt", "text/plain")));
    }

    /** Slow variant that sleeps past the request timeout. */
    private static SkillScriptPlatforms.SkillScriptToolDeclaration slowDeclaration() {
        byte[] slowEntry = entryScript(true);
        return new SkillScriptPlatforms.SkillScriptToolDeclaration(
                new ToolName("sleep_note"),
                "Sleep past the timeout",
                "Sleeps past the requested timeout so the run fails closed.",
                new ToolSchema(
                        "haifa.skillscript.sleep.input",
                        "1.0.0",
                        Map.of(
                                "$schema",
                                ToolSchema.DRAFT_2020_12,
                                "type",
                                "object",
                                "additionalProperties",
                                false,
                                "properties",
                                Map.of())),
                new ToolSchema(
                        "haifa.skillscript.sleep.output",
                        "1.0.0",
                        Map.of("$schema", ToolSchema.DRAFT_2020_12, "type", "object", "additionalProperties", false)),
                Duration.ofMinutes(2),
                binding(packageFiles(slowEntry)),
                scriptRelative(),
                runtimeLanguage(),
                Duration.ofSeconds(1),
                "force the honored timeout path",
                arguments -> TrustedScriptArguments.atWorkspaceRoot(List.of()),
                List.of(new SkillScriptPlatforms.SkillScriptOutput("result.txt", "text/plain")));
    }

    /**
     * Appends the helper marker after reading the helper file relative to the executed entry file
     * itself, proving entry-file identity; {@code slow} sleeps past the timeout instead.
     */
    private static byte[] entryScript(boolean slow) {
        String script = slow
                ? (isWindows()
                        ? "$here = $PSScriptRoot\nWrite-Output 'waiting'\nStart-Sleep -Seconds 30\nWrite-Output 'done'\n"
                        : "here=$(dirname \"$0\")\necho waiting\nsleep 30\necho done\n")
                : (isWindows()
                        ? "$here = $PSScriptRoot\n"
                                + "$note = Get-Content -Raw (Join-Path $here 'helpers/note.txt')\n"
                                + "if (-not (Test-Path outputs)) { New-Item -ItemType Directory -Force -Path outputs | Out-Null }\n"
                                + "[System.IO.File]::AppendAllText('outputs/result.txt', $note + \"`n\")\n"
                                + "Write-Output 'ok'\n"
                        : "here=$(dirname \"$0\")\n"
                                + "mkdir -p outputs\n"
                                + "note=$(cat \"$here/helpers/note.txt\")\n"
                                + "printf '%s\\n' \"$note\" >> outputs/result.txt\n"
                                + "echo ok\n");
        return script.getBytes(StandardCharsets.UTF_8);
    }

    private static String scriptRelative() {
        return "scripts/main" + (isWindows() ? ".ps1" : ".sh");
    }

    private static Map<String, byte[]> packageFiles(byte[] entry) {
        return new LinkedHashMap<>(Map.of(
                "SKILL.md", bytes("# fixture\n"), "scripts/helpers/note.txt", bytes(MARKER), scriptRelative(), entry));
    }

    private static FrozenSkillBinding binding(Map<String, byte[]> files) {
        SkillResourceRef instruction = resource("SKILL.md", SkillResourceKind.INSTRUCTION, "text/markdown", files);
        SkillResourceRef helper =
                resource("scripts/helpers/note.txt", SkillResourceKind.REFERENCE, "text/plain", files);
        SkillResourceRef script = resource(scriptRelative(), SkillResourceKind.SCRIPT, "text/plain", files);
        SkillContentDigest packageDigest = new SkillContentDigest("sha256:" + "a".repeat(64));
        SkillName name = new SkillName("trusted-entry-package");
        SkillDeclaredVersion version = new SkillDeclaredVersion("1.0.0");
        SkillPackageIndex index = new SkillPackageIndex(packageDigest, List.of(instruction, helper, script));
        var coordinate = new SkillCoordinate(
                SkillScopeRef.product(),
                new SkillSourceRef("starter-tests", "1"),
                name,
                Optional.of(version),
                packageDigest);
        return new FrozenSkillBinding(
                new SkillAlias("trusted-entry-package"),
                coordinate,
                new SkillMetadata(
                        name,
                        "Materializes a bounded package",
                        Optional.of(version),
                        Optional.empty(),
                        Optional.empty(),
                        Map.of(),
                        java.util.Set.of()),
                index,
                packageDigest,
                new SkillContentDigest("sha256:" + "c".repeat(64)),
                "starter-tests",
                Optional.empty());
    }

    private static SkillResourceRef resource(
            String relativePath, SkillResourceKind kind, String mediaType, Map<String, byte[]> files) {
        byte[] bytes = files.get(relativePath);
        return new SkillResourceRef(
                relativePath, kind, mediaType, SkillTrustDigests.content(bytes), bytes.length, true);
    }

    private static SkillContentLoader loader(Map<String, byte[]> files) {
        return new SkillContentLoader() {
            @Override
            public SkillContent load(FrozenSkillBinding binding, SkillVisibilityContext ignored) {
                return new SkillContent(binding, "# fixture", Map.of("SKILL.md", "# fixture"), 4);
            }

            @Override
            public Optional<byte[]> loadResourceBytes(
                    FrozenSkillBinding ignoredBinding, SkillVisibilityContext ignored, String relativePath) {
                return Optional.ofNullable(files.get(relativePath));
            }
        };
    }

    private static FrozenToolBinding freeze(ToolRegistration registration) {
        var catalog = new ToolCatalogBuilder();
        catalog.register(
                registration.alias(),
                registration.definition(),
                registration.providerBindingReference(),
                registration.provider());
        return catalog.freeze().snapshot().bindings().getFirst();
    }

    private static ToolInvocationRequest invocation(FrozenToolBinding binding, ToolDefinition definition, String key) {
        return new ToolInvocationRequest(
                binding,
                new ToolCallId(key),
                new AgentRunId("skill-script-run-" + key),
                TENANT,
                PRINCIPAL,
                new ToolArguments(
                        definition.inputSchema().id(), definition.inputSchema().version(), Map.of()),
                Instant.now().plus(Duration.ofMinutes(5)),
                Optional.of("invocation-" + key),
                () -> false,
                Map.of());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> outputs(io.haifa.agent.core.tool.ToolResult result) {
        Object value = result.structuredData().get("outputs");
        assertThat(value).isInstanceOf(List.class);
        return (List<Map<String, Object>>) value;
    }

    private static Object output(List<Map<String, Object>> outputs, String key) {
        return outputs.get(0).get(key);
    }

    private static Artifact findArtifact(ArtifactService artifacts, Object artifactId) {
        List<Artifact> candidates = artifacts.findByProject(PROJECT.projectId());
        return candidates.stream()
                .filter(candidate -> candidate.id().value().equals(artifactId))
                .findFirst()
                .orElseThrow();
    }

    private static ArtifactService artifacts() {
        IdentifierGenerator identifiers = new UuidV7IdentifierGenerator();
        return new ArtifactService(
                new InMemoryArtifactStore(),
                new InMemoryArtifactPayloadStore(),
                identifiers,
                Clock.systemUTC()::instant);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static Path createWorkspace(Path root, String name) throws java.io.IOException {
        return Files.createDirectories(root.resolve(name));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String runtimeLanguage() {
        return isWindows() ? "powershell" : "sh";
    }

    private static Path runtimeExecutable() {
        if (isWindows()) {
            return Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        }
        return Path.of("/bin/sh");
    }
}

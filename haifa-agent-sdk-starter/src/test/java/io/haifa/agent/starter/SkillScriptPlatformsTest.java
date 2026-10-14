package io.haifa.agent.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.execution.core.tool.ExecutionToolDefinitionFactory;
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
import io.haifa.agent.tool.api.ToolCoordinate;
import io.haifa.agent.tool.api.ToolDefinition;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolName;
import io.haifa.agent.tool.api.ToolSchema;
import io.haifa.agent.tool.core.ToolCatalogBuilder;
import io.haifa.agent.tool.core.ToolDefinitionCanonicalizer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Entry-file trusted Skill script execution end to end through the real host sandbox: one prepared
 * immutable version directory per Skill version, reuse without re-reading frozen bytes, fail-closed
 * drift rejection, outputs that stay in the product-owned {@code outputs/} chain, and
 * fail-closed runtime preflight.
 */
class SkillScriptPlatformsTest {
    private static final TenantRef TENANT = new TenantRef("public");
    private static final PrincipalRef PRINCIPAL = new PrincipalRef("default-public-user", "user");
    private static final Duration MAXIMUM_TIMEOUT = Duration.ofMinutes(10);
    static final String MARKER = "helper marker v1";
    static final String STALE = "stale prefix from an earlier invocation\n";
    private static final String EXPECTED_OUTPUT = STALE + MARKER + "\n";

    @Test
    void executesTheEntryFileKeepsOutputsInPlaceAndReusesTheVersionDirectory(@TempDir Path temporary) throws Exception {
        assumeThat(runtimeExecutable()).matches(path -> path.toFile().isFile());
        Path workspace = createWorkspace(temporary, "workspace");
        Path dataRoot = createWorkspace(temporary, "data");
        Files.createDirectories(workspace.resolve("outputs"));
        Files.writeString(workspace.resolve("outputs").resolve("result.txt"), STALE, StandardCharsets.UTF_8);
        CountingLoader loader = new CountingLoader(packageFiles(entryScript(false)));
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

        StaticLoader loader = new StaticLoader(packageFiles(entryScript(true)));
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
    void failsClosedWhenTheFrozenPackageDriftsOrTheRuntimeIsNotDeclared(@TempDir Path temporary) throws Exception {
        Path workspace = createWorkspace(temporary, "workspace");
        Path dataRoot = createWorkspace(temporary, "data");
        Files.createDirectories(workspace.resolve("outputs"));
        Files.writeString(workspace.resolve("outputs").resolve("result.txt"), STALE, StandardCharsets.UTF_8);
        CountingLoader loader = new CountingLoader(packageFiles(entryScript(false)));
        Map<String, byte[]> driftedBytes = new LinkedHashMap<>(packageFiles(entryScript(false)));
        driftedBytes.put(
                "scripts/helpers/note.txt", "drifted bytes for the rejection path".getBytes(StandardCharsets.UTF_8));
        var driftedLoader = new StaticLoader(driftedBytes, loader.frozen);
        var spec = spec(workspace, dataRoot, driftedLoader, List.of(declaration(loader.frozen)));

        try (SkillScriptPlatforms.SkillScriptPlatform platform =
                SkillScriptPlatforms.trusted(spec, TENANT, PRINCIPAL)) {
            var registration = platform.registrations().get(0);
            FrozenToolBinding binding = freeze(registration);
            assertThatThrownBy(() -> registration
                            .provider()
                            .invoke(invocation(binding, registration.definition(), "drifted-invoke-1")))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("package content has drifted");
            // Nothing was prepared: a version directory that was never completed is not reused,
            // and no package content was placed or executed.
            assertThat(Files.exists(workspace.resolve(SkillScriptPackageDirectory.DIRECTORY)))
                    .isFalse();
            assertThat(Files.readString(workspace.resolve("outputs").resolve("result.txt"), StandardCharsets.UTF_8))
                    .isEqualTo(STALE);
        }

        // The Tool's declared runtime is not among the platform runtimes: fail closed with the
        // stable code before any Tool is registered on a builder.
        var rebuilt = new SkillScriptPlatforms.SkillScriptSpec(
                workspace,
                dataRoot,
                List.of(new SkillScriptPlatforms.SkillScriptRuntime("python", runtimeExecutable())),
                loader,
                List.of(declaration(loader.frozen)),
                Duration.ofSeconds(90),
                MAXIMUM_TIMEOUT,
                65_536,
                1_000);
        assertThatThrownBy(() -> SkillScriptPlatforms.trusted(rebuilt, TENANT, PRINCIPAL))
                .isInstanceOf(HaifaAgentException.class)
                .hasFieldOrPropertyWithValue("code", "SKILL_SCRIPT_RUNTIME_UNAVAILABLE");
    }

    /** R3: different Skill package versions or entries produce different frozen Tool identities. */
    @Test
    void differentSkillPackagesAndEntriesBindToDifferentFrozenToolIdentities(@TempDir Path temporary)
            throws java.io.IOException {
        Path workspace = createWorkspace(temporary, "workspace");
        Path dataRoot = createWorkspace(temporary, "data");
        Map<String, byte[]> filesA = packageFiles(entryScript(false));
        FrozenSkillBinding bindingA = loaderBinding(filesA);
        CountingLoader loader = new CountingLoader(filesA, bindingA);
        Map<String, byte[]> filesB = packageFiles(entryScript(false, "echo entry B\n"));
        FrozenSkillBinding bindingB = loaderBinding(filesB);
        var declarationA = declaration(bindingA);
        var specA = spec(workspace, dataRoot, loader, List.of(declarationA));
        SkillScriptPlatforms.SkillScriptSpec specB = otherEntrySpec(workspace, dataRoot, filesB, bindingB);

        try (var platformA = SkillScriptPlatforms.trusted(specA, TENANT, PRINCIPAL);
                var platformB = SkillScriptPlatforms.trusted(specB, TENANT, PRINCIPAL)) {
            ToolDefinition definitionA = platformA.registrations().get(0).definition();
            ToolDefinition definitionB = platformB.registrations().get(0).definition();
            var canonicalizer = new ToolDefinitionCanonicalizer();

            // Different frozen entry bytes always change the definitionHash - and with it the
            // frozen Tool coordinate that the Runtime can bind to.
            assertThat(canonicalizer.hash(definitionA)).isNotEqualTo(canonicalizer.hash(definitionB));

            // The same Skill version and entry reassemble reproducibly: the identity is stable.
            try (var platformA2 = SkillScriptPlatforms.trusted(specA, TENANT, PRINCIPAL)) {
                assertThat(canonicalizer.hash(platformA2.registrations().get(0).definition()))
                        .isEqualTo(canonicalizer.hash(definitionA));
            }

            // The recovery path: a catalog frozen against entry A cannot resolve the binding
            // coordinate of entry B, so a resumed Run fails closed instead of silently running
            // the changed script.
            var catalogA = new ToolCatalogBuilder();
            catalogA.register(
                    platformA.registrations().get(0).alias(),
                    definitionA,
                    platformA.registrations().get(0).providerBindingReference(),
                    platformA.registrations().get(0).provider());
            var bindingsA = catalogA.freeze().snapshot().bindings();
            assertThat(bindingsA).anyMatch(b -> b.coordinate().equals(coordinate(definitionA, canonicalizer)));
            assertThat(bindingsA).noneMatch(b -> b.coordinate().equals(coordinate(definitionB, canonicalizer)));
        }
    }

    private static SkillScriptPlatforms.SkillScriptSpec otherEntrySpec(
            Path workspace, Path dataRoot, Map<String, byte[]> files, FrozenSkillBinding binding) {
        SkillScriptPlatforms.SkillScriptToolDeclaration declarationB =
                new SkillScriptPlatforms.SkillScriptToolDeclaration(
                        new ToolName("generate_note"),
                        "Generate one bounded note",
                        "Generates one bounded note artifact through the frozen skill package script.",
                        inputSchema(),
                        Duration.ofMinutes(2),
                        binding,
                        scriptRelative(),
                        runtimeLanguage(),
                        Duration.ofSeconds(60),
                        "verify the trusted package entry run",
                        arguments -> TrustedScriptArguments.atWorkspaceRoot(List.of()));
        return spec(workspace, dataRoot, new StaticLoader(files, binding), List.of(declarationB));
    }

    private static ToolCoordinate coordinate(ToolDefinition definition, ToolDefinitionCanonicalizer canonicalizer) {
        return new ToolCoordinate(
                definition.name(), definition.version(), definition.providerId(), canonicalizer.hash(definition));
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

    private static SkillScriptPlatforms.SkillScriptSpec spec(
            Path workspace,
            Path dataRoot,
            SkillContentLoader loader,
            List<SkillScriptPlatforms.SkillScriptToolDeclaration> declarations) {
        return new SkillScriptPlatforms.SkillScriptSpec(
                workspace,
                dataRoot,
                List.of(new SkillScriptPlatforms.SkillScriptRuntime(runtimeLanguage(), runtimeExecutable())),
                loader,
                declarations,
                Duration.ofSeconds(90),
                MAXIMUM_TIMEOUT,
                65_536,
                1_000);
    }

    private static SkillScriptPlatforms.SkillScriptToolDeclaration declaration(FrozenSkillBinding binding) {
        return new SkillScriptPlatforms.SkillScriptToolDeclaration(
                new ToolName("generate_note"),
                "Generate one bounded note",
                "Generates one bounded note artifact through the frozen skill package script.",
                inputSchema(),
                Duration.ofMinutes(2),
                binding,
                scriptRelative(),
                runtimeLanguage(),
                Duration.ofSeconds(60),
                "verify the trusted package entry run",
                arguments -> TrustedScriptArguments.atWorkspaceRoot(List.of()));
    }

    private static SkillScriptPlatforms.SkillScriptToolDeclaration slowDeclaration() {
        return new SkillScriptPlatforms.SkillScriptToolDeclaration(
                new ToolName("sleep_note"),
                "Sleep past the timeout",
                "Sleeps past the requested timeout so the run fails closed.",
                inputSchema(),
                Duration.ofMinutes(2),
                loaderBinding(packageFiles(entryScript(true))),
                scriptRelative(),
                runtimeLanguage(),
                Duration.ofSeconds(1),
                "force the honored timeout path",
                arguments -> TrustedScriptArguments.atWorkspaceRoot(List.of()));
    }

    private static ToolSchema inputSchema() {
        return new ToolSchema(
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
                        Map.of()));
    }

    /**
     * Appends the relative-import helper marker to {@code outputs/result.txt}, proving entry-file
     * identity; {@code slow} sleeps past the timeout instead; {@code extra} appends one extra
     * script statement so another declaration's frozen bytes differ.
     */
    static byte[] entryScript(boolean slow) {
        return entryScript(slow, "");
    }

    static byte[] entryScript(boolean slow, String extra) {
        String script;
        if (slow) {
            script = isWindows()
                    ? "Write-Output 'waiting'\nStart-Sleep -Seconds 30\nWrite-Output 'done'\n"
                    : "echo waiting\nsleep 30\necho done\n";
        } else {
            script = isWindows()
                    ? "$here = $PSScriptRoot\n"
                            + "$note = Get-Content -Raw (Join-Path $here 'helpers/note.txt')\n"
                            + "if (-not (Test-Path outputs)) { New-Item -ItemType Directory -Force -Path outputs | Out-Null }\n"
                            + "$existing = ''\n"
                            + "if (Test-Path 'outputs/result.txt') { $existing = Get-Content -Raw 'outputs/result.txt' }\n"
                            + "[System.IO.File]::WriteAllText('outputs/result.txt', $existing + $note + \"`n\")\n"
                            + "Write-Output 'ok'\n"
                    : ("here=$(dirname \"$0\")\n"
                            + "mkdir -p outputs\n"
                            + "note=$(cat \"$here/helpers/note.txt\")\n"
                            + "touch outputs/result.txt\n"
                            + "printf '%s\\n' \"$note\" >> outputs/result.txt\n"
                            + "echo ok\n");
        }
        return (script + extra).getBytes(StandardCharsets.UTF_8);
    }

    static String scriptRelative() {
        return "scripts/main" + (isWindows() ? ".ps1" : ".sh");
    }

    static Map<String, byte[]> packageFiles(byte[] entry) {
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", skillMd());
        files.put("scripts/helpers/note.txt", MARKER.getBytes(StandardCharsets.UTF_8));
        files.put(scriptRelative(), entry);
        return files;
    }

    private static byte[] skillMd() {
        return ("---\n"
                        + "name: trusted-entry-package\n"
                        + "description: Provides a reviewed multi-file script package for starter tests.\n"
                        + "metadata:\n"
                        + "  haifa.version: 1.0.0\n"
                        + "---\n"
                        + "# fixture\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Loader fixture that counts every frozen byte read, one fixed frozen binding. */
    static final class CountingLoader implements SkillContentLoader {
        final Map<String, byte[]> files;
        final FrozenSkillBinding frozen;
        final AtomicInteger loads = new AtomicInteger();

        CountingLoader(Map<String, byte[]> files) {
            this(files, loaderBinding(files));
        }

        CountingLoader(Map<String, byte[]> files, FrozenSkillBinding frozen) {
            this.files = files;
            this.frozen = frozen;
        }

        @Override
        public SkillContent load(FrozenSkillBinding binding, SkillVisibilityContext ignored) {
            return new SkillContent(binding, "# fixture", Map.of("SKILL.md", "# fixture"), 4);
        }

        @Override
        public Optional<byte[]> loadResourceBytes(
                FrozenSkillBinding ignoredBinding, SkillVisibilityContext ignored, String relativePath) {
            loads.incrementAndGet();
            return Optional.ofNullable(files.get(relativePath));
        }
    }

    /** Loader fixture that serves exactly one fixed file map for one fixed binding. */
    static final class StaticLoader implements SkillContentLoader {
        final Map<String, byte[]> files;
        final FrozenSkillBinding frozen;

        StaticLoader(Map<String, byte[]> files, FrozenSkillBinding frozen) {
            this.files = files;
            this.frozen = frozen;
        }

        StaticLoader(Map<String, byte[]> files) {
            this(files, loaderBinding(files));
        }

        @Override
        public SkillContent load(FrozenSkillBinding binding, SkillVisibilityContext ignored) {
            return new SkillContent(binding, "# fixture", Map.of("SKILL.md", "# fixture"), 4);
        }

        @Override
        public Optional<byte[]> loadResourceBytes(
                FrozenSkillBinding ignoredBinding, SkillVisibilityContext ignored, String relativePath) {
            return Optional.ofNullable(files.get(relativePath));
        }
    }

    /** Builds a frozen binding whose resource index carries the exact served bytes. */
    static FrozenSkillBinding loaderBinding(Map<String, byte[]> files) {
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

    static FrozenToolBinding freeze(ToolRegistration registration) {
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

    static Path createWorkspace(Path root, String name) throws java.io.IOException {
        return Files.createDirectories(root.resolve(name));
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    static String runtimeLanguage() {
        return isWindows() ? "powershell" : "sh";
    }

    static Path runtimeExecutable() {
        if (isWindows()) {
            return Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        }
        return resolveUnixShellRuntime();
    }

    /**
     * Resolves one exact, executable Unix shell file for the declared trusted runtime, mirroring
     * the product contract: {@code /bin/sh} is a symlink to dash on Debian and Ubuntu, and the
     * runtime preflight requires an explicit absolute path naming a real regular file, so the
     * declared executable is the canonicalized target instead of a platform-hardcoded symlink.
     */
    static Path resolveUnixShellRuntime() {
        for (String candidate : List.of("/bin/sh", "/usr/bin/sh", "/bin/bash", "/usr/bin/bash")) {
            try {
                Path declared = Path.of(candidate).toRealPath();
                if (Files.isRegularFile(declared, LinkOption.NOFOLLOW_LINKS) && Files.isExecutable(declared)) {
                    return declared;
                }
            } catch (java.io.IOException ignored) {
                // Try the next candidate; a missing or dangling shell is not itself a test failure.
            }
        }
        throw new IllegalStateException("no exact executable Unix shell runtime was found for this host");
    }
}

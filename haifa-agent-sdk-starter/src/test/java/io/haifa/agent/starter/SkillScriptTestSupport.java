package io.haifa.agent.starter;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.execution.core.tool.TrustedScriptArguments;
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
import io.haifa.agent.tool.api.ToolDefinition;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolName;
import io.haifa.agent.tool.api.ToolSchema;
import io.haifa.agent.tool.core.ToolCatalogBuilder;
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

/** Shared package/host fixtures for the starter's three independent wiring checks. */
final class SkillScriptTestSupport {
    static final TenantRef TENANT = new TenantRef("public");
    static final PrincipalRef PRINCIPAL = new PrincipalRef("default-public-user", "user");
    static final Duration MAXIMUM_TIMEOUT = Duration.ofMinutes(10);
    static final String MARKER = "helper marker v1";
    static final String STALE = "stale prefix from an earlier invocation\n";
    static final String EXPECTED_OUTPUT = STALE + MARKER + "\n";

    private SkillScriptTestSupport() {}

    static SkillScriptPlatforms.SkillScriptSpec spec(
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

    static SkillScriptPlatforms.SkillScriptToolDeclaration declaration(FrozenSkillBinding binding) {
        return declaration(binding, runtimeLanguage(), Duration.ofSeconds(60), "verify the trusted package entry run");
    }

    static SkillScriptPlatforms.SkillScriptToolDeclaration declaration(
            FrozenSkillBinding binding, String runtime, Duration timeout, String purpose) {
        return new SkillScriptPlatforms.SkillScriptToolDeclaration(
                new ToolName("generate_note"),
                "Generate one bounded note",
                "Generates one bounded note artifact through the frozen skill package script.",
                inputSchema(),
                Duration.ofMinutes(2),
                binding,
                scriptRelative(),
                runtime,
                timeout,
                purpose,
                arguments -> TrustedScriptArguments.atWorkspaceRoot(List.of()));
    }

    static SkillScriptPlatforms.SkillScriptToolDeclaration slowDeclaration() {
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

    static ToolSchema inputSchema() {
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
     * identity; {@code slow} sleeps past the timeout instead.
     */
    static byte[] entryScript(boolean slow) {
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
        return script.getBytes(StandardCharsets.UTF_8);
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

    static byte[] skillMd() {
        return ("---\n"
                        + "name: trusted-entry-package\n"
                        + "description: Provides a reviewed multi-file script package for starter tests.\n"
                        + "metadata:\n"
                        + "  haifa.version: 1.0.0\n"
                        + "---\n"
                        + "# fixture\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Loader fixture that serves one fixed file map and pauses its first byte read. */

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

    static SkillResourceRef resource(
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

    static ToolInvocationRequest invocation(FrozenToolBinding binding, ToolDefinition definition, String key) {
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

    /** Fixed package bytes with an optional frozen index; counts reads to verify reuse. */
    static final class SkillPackageLoader implements SkillContentLoader {
        final Map<String, byte[]> files;
        final FrozenSkillBinding frozen;
        final AtomicInteger loads = new AtomicInteger();

        SkillPackageLoader(Map<String, byte[]> files) {
            this.files = files;
            this.frozen = loaderBinding(files);
        }

        @Override
        public SkillContent load(FrozenSkillBinding binding, SkillVisibilityContext ignored) {
            return new SkillContent(binding, "# fixture", Map.of("SKILL.md", "# fixture"), 4);
        }

        @Override
        public Optional<byte[]> loadResourceBytes(
                FrozenSkillBinding binding, SkillVisibilityContext ignored, String path) {
            loads.incrementAndGet();
            return Optional.ofNullable(files.get(path));
        }
    }
}

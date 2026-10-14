package io.haifa.agent.execution.core.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.execution.api.ExecutionBroker;
import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.ExecutionEnvironmentRef;
import io.haifa.agent.execution.api.ExecutionId;
import io.haifa.agent.execution.api.ExecutionInput;
import io.haifa.agent.execution.api.ExecutionOutput;
import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.execution.api.ExecutionResult;
import io.haifa.agent.execution.api.ExecutionStatus;
import io.haifa.agent.execution.api.ResourceUsageSummary;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.workspace.WorkspaceId;
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
import io.haifa.agent.tool.api.SemanticVersion;
import io.haifa.agent.tool.api.ToolAlias;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolCoordinate;
import io.haifa.agent.tool.api.ToolDefinition;
import io.haifa.agent.tool.api.ToolDefinitionHash;
import io.haifa.agent.tool.api.ToolExecutionMode;
import io.haifa.agent.tool.api.ToolIdempotency;
import io.haifa.agent.tool.api.ToolInvocationObserver;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolName;
import io.haifa.agent.tool.api.ToolResourceRequirements;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSchema;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Entry-file trusted execution: materialization, drift rejection, cleanup and frozen argv. */
class TrustedSkillEntryFileExecutionTest {
    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");
    private static final byte[] HELPER = "HELPER".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ENTRY = "ENTRY".getBytes(StandardCharsets.UTF_8);
    private static final Map<String, byte[]> PACKAGE_FILES = Map.of(
            "SKILL.md", "# fixture".getBytes(StandardCharsets.UTF_8),
            "helpers/util.txt", HELPER,
            "scripts/main", ENTRY);

    private static final ScriptRuntimeAdapter ENTRY_ADAPTER = new ScriptRuntimeAdapter() {
        @Override
        public String language() {
            return "fixture-runtime";
        }

        @Override
        public String executable() {
            return "trusted-runtime";
        }

        @Override
        public PreparedScript prepare(String content, List<String> arguments) {
            throw new UnsupportedOperationException("fixture entry runtime requires entry files");
        }

        @Override
        public boolean supportsStandardInput() {
            return false;
        }

        @Override
        public boolean supportsFileExecution() {
            return true;
        }

        @Override
        public PreparedScript prepareFile(String workspaceRelativeEntryPath, List<String> arguments) {
            List<String> argv = new ArrayList<>();
            argv.add(executable());
            argv.add(workspaceRelativeEntryPath);
            argv.addAll(arguments);
            return new PreparedScript(ExecutionCommand.direct(argv), ExecutionInput.none());
        }
    };

    private static final ScriptRuntimeAdapter STDIN_ADAPTER = new ScriptRuntimeAdapter() {
        @Override
        public String language() {
            return "fixture-runtime";
        }

        @Override
        public String executable() {
            return "trusted-runtime";
        }

        @Override
        public PreparedScript prepare(String content, List<String> arguments) {
            List<String> argv = new ArrayList<>();
            argv.add(executable());
            argv.addAll(arguments);
            return new PreparedScript(
                    ExecutionCommand.direct(argv),
                    ExecutionInput.utf8(content.endsWith("\n") ? content : content + "\n"));
        }
    };

    @Test
    void materializesTheFrozenPackageAndExecutesTheEntryFileByPath() {
        var captured = new AtomicReference<ExecutionRequest>();
        RecordingMaterialization materialization = new RecordingMaterialization();
        TrustedFixture fixture = assemble(
                executionProvider(
                        brokerCapturing(captured),
                        Set.of("execution_run"),
                        true,
                        (ignoredWorkspace, ignoredPaths) -> {}),
                contentLoader(PACKAGE_FILES),
                materialization,
                List.of());

        ToolResult result = fixture.provider().invoke(invocation(fixture.binding()));

        assertThat(result.successful()).isTrue();
        assertThat(materialization.released.get()).isEqualTo(1);
        assertThat(materialization.staged.get()).isNotNull();
        assertThat(materialization.staged.get().materializationKey()).matches("[0-9a-f]{64}");
        assertThat(materialization.staged.get().workspaceRelativeEntryPath())
                .startsWith(".haifa-trusted-skills/")
                .endsWith("/scripts/main");
        assertThat(materialization.filesReceived)
                .extracting(TrustedSkillScriptMaterialization.PackageFile::relativePath)
                .containsExactlyInAnyOrder("SKILL.md", "helpers/util.txt", "scripts/main");
        assertThat(TrustedSkillEntryFileExecutionTest.bytesOf(materialization.filesReceived, "scripts/main"))
                .isEqualTo(ENTRY);
        assertThat(TrustedSkillEntryFileExecutionTest.bytesOf(materialization.filesReceived, "helpers/util.txt"))
                .isEqualTo(HELPER);
        // The entry file is executed by Workspace-relative path with empty standard input.
        ExecutionRequest request = captured.get();
        assertThat(request.command().argv())
                .containsExactly(
                        "trusted-runtime", materialization.staged.get().workspaceRelativeEntryPath(), "MODEL_VALUE");
        assertThat(request.input().isEmpty()).isTrue();
        assertThat(request.workingDirectory().projectPath().isRoot()).isTrue();
        assertThat(request.limits().timeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void rejectsFrozenPackageDriftBeforeAnyPlacementOrProcessStart() {
        var captured = new AtomicReference<ExecutionRequest>();
        var driftedFiles = new LinkedHashMap<String, byte[]>(PACKAGE_FILES);
        driftedFiles.put("helpers/util.txt", "DRIFTED".getBytes(StandardCharsets.UTF_8));
        RecordingMaterialization materialization = new RecordingMaterialization();
        TrustedFixture fixture = assemble(
                executionProvider(
                        brokerCapturing(captured),
                        Set.of("execution_run"),
                        true,
                        (ignoredWorkspace, ignoredPaths) -> {}),
                contentLoader(driftedFiles),
                materialization,
                List.of());

        assertThatThrownBy(() -> fixture.provider().invoke(invocation(fixture.binding())))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("package content has drifted");
        assertThat(materialization.staged.get()).isNull();
        assertThat(materialization.released.get()).isEqualTo(0);
        assertThat(captured.get()).isNull();
    }

    @Test
    void failsClosedWhenFrozenBytesAreUnavailable() {
        var captured = new AtomicReference<ExecutionRequest>();
        RecordingMaterialization materialization = new RecordingMaterialization();
        TrustedFixture fixture = assemble(
                executionProvider(
                        brokerCapturing(captured),
                        Set.of("execution_run"),
                        true,
                        (ignoredWorkspace, ignoredPaths) -> {}),
                contentLoader(Map.of()),
                materialization,
                List.of());

        assertThatThrownBy(() -> fixture.provider().invoke(invocation(fixture.binding())))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("package bytes are unavailable");
        assertThat(materialization.staged.get()).isNull();
        assertThat(captured.get()).isNull();
    }

    @Test
    void releasesTheMaterializedPackageEvenWhenExecutionFails() {
        RecordingMaterialization materialization = new RecordingMaterialization();
        TrustedFixture fixture = assemble(
                executionProvider(
                        brokerFailing(), Set.of("execution_run"), true, (ignoredWorkspace, ignoredPaths) -> {}),
                contentLoader(PACKAGE_FILES),
                materialization,
                List.of());

        assertThatThrownBy(() -> fixture.provider().invoke(invocation(fixture.binding())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("execution survived failure");
        assertThat(materialization.released.get()).isEqualTo(1);
    }

    @Test
    void rejectsRuntimesWithoutEntryFileExecution() {
        var captured = new AtomicReference<ExecutionRequest>();
        RecordingMaterialization materialization = new RecordingMaterialization();
        TrustedFixture fixture = assemble(
                executionProvider(
                        brokerCapturing(captured),
                        Set.of("execution_run"),
                        false,
                        (ignoredWorkspace, ignoredPaths) -> {}),
                contentLoader(PACKAGE_FILES),
                materialization,
                List.of());

        assertThatThrownBy(() -> fixture.provider().invoke(invocation(fixture.binding())))
                .hasMessageContaining("does not support entry-file execution");
        assertThat(materialization.staged.get()).isNull();
        assertThat(captured.get()).isNull();
    }

    @Test
    void workspaceInputsRemainValidatedAndWorkdirStaysRoot() {
        var captured = new AtomicReference<ExecutionRequest>();
        var validated = new AtomicReference<List<ProjectPath>>(null);
        RecordingMaterialization materialization = new RecordingMaterialization();
        TrustedFixture fixture = assemble(
                executionProvider(
                        brokerCapturing(captured),
                        Set.of("execution_run"),
                        true,
                        (workspaceId, paths) -> validated.set(paths)),
                contentLoader(PACKAGE_FILES),
                materialization,
                List.of(ProjectPath.of("inputs/a.txt")));

        fixture.provider().invoke(invocation(fixture.binding()));

        assertThat(validated.get()).isEqualTo(List.of(ProjectPath.of("inputs/a.txt")));
        assertThat(captured.get().workingDirectory().workspaceId()).isEqualTo(new WorkspaceId("workspace"));
        assertThat(captured.get().workingDirectory().projectPath().isRoot()).isTrue();
    }

    private record TrustedFixture(
            TrustedSkillScriptToolProvider provider,
            FrozenToolBinding binding,
            RecordingMaterialization materialization) {}

    private static TrustedFixture assemble(
            ExecutionToolProvider execution,
            SkillContentLoader content,
            RecordingMaterialization materialization,
            List<ProjectPath> workspaceInputs) {
        String executionDigest = execution.configurationIdentity();
        String sandboxDigest = SkillTrustDigests.sandbox(execution.sandboxProfileIdentity());
        ToolDefinition definition = definition(executionDigest, sandboxDigest);
        TrustedSkillScriptToolSpec spec = new TrustedSkillScriptToolSpec(
                definition,
                binding(),
                "scripts/main",
                SkillTrustDigests.content(ENTRY),
                "fixture-runtime",
                executionDigest,
                sandboxDigest,
                Set.of("execution_run"),
                Duration.ofSeconds(5),
                "entry fixture",
                arguments -> TrustedScriptArguments.atWorkspaceRootWithInputs(
                        List.of(String.valueOf(arguments.get("value"))), workspaceInputs),
                true);
        TrustedSkillScriptToolProvider provider =
                new TrustedSkillScriptToolProvider(execution, content, materialization, List.of(spec));
        FrozenToolBinding toolBinding = new FrozenToolBinding(
                new ToolAlias("trusted_entry_tool"),
                new ToolCoordinate(
                        definition.name(),
                        definition.version(),
                        definition.providerId(),
                        new ToolDefinitionHash("2".repeat(64))),
                definition,
                "fixture",
                "catalog");
        return new TrustedFixture(provider, toolBinding, materialization);
    }

    private static ExecutionToolProvider executionProvider(
            ExecutionBroker broker,
            Set<String> capabilities,
            boolean entryFileAdapter,
            TrustedWorkspacePathValidator validator) {
        List<ScriptRuntimeAdapter> adapters = List.of(entryFileAdapter ? ENTRY_ADAPTER : STDIN_ADAPTER);
        var runtimes = new ScriptRuntimeResolver(ExecutionOperatingSystem.LINUX, adapters);
        var configuration = new ExecutionToolConfiguration(
                ExecutionEnvironmentRef.empty(),
                new SandboxProfileRef("sandbox", "1"),
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                4096,
                100,
                1,
                false,
                runtimes,
                ignored -> {},
                value -> value);
        return new ExecutionToolProvider(
                broker,
                () -> "execution-id",
                () -> NOW,
                ignored -> new ExecutionInvocationScopeResolver.ExecutionInvocationScope(
                        new WorkspaceId("workspace"), capabilities),
                configuration,
                validator);
    }

    private static ExecutionBroker brokerCapturing(AtomicReference<ExecutionRequest> captured) {
        return new ExecutionBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request) {
                captured.set(request);
                return exited(request.id(), 0);
            }

            @Override
            public boolean cancel(ExecutionId id) {
                return false;
            }

            @Override
            public Optional<ExecutionResult> find(ExecutionId id) {
                return Optional.empty();
            }
        };
    }

    private static ExecutionBroker brokerFailing() {
        return new ExecutionBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request) {
                throw new IllegalStateException("execution survived failure");
            }

            @Override
            public boolean cancel(ExecutionId id) {
                return false;
            }

            @Override
            public Optional<ExecutionResult> find(ExecutionId id) {
                return Optional.empty();
            }
        };
    }

    private static ExecutionResult exited(ExecutionId id, int exitCode) {
        var output = new ExecutionOutput("ok", null, 2, "0".repeat(64), false, false);
        var empty = new ExecutionOutput("", null, 0, "0".repeat(64), false, false);
        return new ExecutionResult(
                id,
                ExecutionStatus.EXITED,
                exitCode,
                NOW,
                NOW.plusMillis(10),
                output,
                empty,
                "sandbox-session",
                new ResourceUsageSummary(Duration.ofMillis(10), 1),
                null,
                false);
    }

    /** Content loader serving frozen bytes per resource, and the frozen text for binding equality. */
    private static SkillContentLoader contentLoader(Map<String, byte[]> files) {
        FrozenSkillBinding executionBinding = binding();
        return new SkillContentLoader() {
            @Override
            public SkillContent load(FrozenSkillBinding ignored, SkillVisibilityContext ignored2) {
                return new SkillContent(executionBinding, "# fixture", Map.of("SKILL.md", "# fixture"), 4);
            }

            @Override
            public java.util.Optional<byte[]> loadResourceBytes(
                    FrozenSkillBinding ignored, SkillVisibilityContext ignored2, String relativePath) {
                return java.util.Optional.ofNullable(files.get(relativePath));
            }
        };
    }

    private static FrozenSkillBinding binding() {
        // Entry-file execution verifies EVERY indexed resource against the frozen index, so the
        // fixture index carries the real digests of the exact bytes the loader serves.
        SkillResourceRef instruction =
                ref(PACKAGE_FILES.get("SKILL.md"), SkillResourceKind.INSTRUCTION, "text/markdown");
        SkillResourceRef helperRef = ref(HELPER, SkillResourceKind.REFERENCE, "text/plain");
        SkillResourceRef script = ref(ENTRY, SkillResourceKind.SCRIPT, "text/plain");
        SkillContentDigest packageDigest = new SkillContentDigest("sha256:" + "a".repeat(64));
        SkillName name = new SkillName("trusted-entry-package");
        SkillDeclaredVersion version = new SkillDeclaredVersion("1.0.0");
        SkillPackageIndex index = new SkillPackageIndex(packageDigest, List.of(instruction, helperRef, script));
        var coordinate = new SkillCoordinate(
                SkillScopeRef.product(), new SkillSourceRef("fixture", "1"), name, Optional.of(version), packageDigest);
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
                        Set.of()),
                index,
                packageDigest,
                new SkillContentDigest("sha256:" + "c".repeat(64)),
                "fixture",
                Optional.of("package-grant"));
    }

    private static SkillResourceRef ref(byte[] bytes, SkillResourceKind kind, String mediaType) {
        String path =
                switch (kind) {
                    case INSTRUCTION -> "SKILL.md";
                    case REFERENCE -> "helpers/util.txt";
                    case SCRIPT -> "scripts/main";
                    default -> throw new IllegalArgumentException("fixture kinds only");
                };
        return new SkillResourceRef(path, kind, mediaType, SkillTrustDigests.content(bytes), bytes.length, true);
    }

    private static ToolDefinition definition(String executionDigest, String sandboxDigest) {
        ToolName name = new ToolName("trusted_entry_tool");
        Map<String, Object> input = Map.of(
                "$schema",
                ToolSchema.DRAFT_2020_12,
                "type",
                "object",
                "additionalProperties",
                false,
                "properties",
                Map.of("value", Map.of("type", "string")));
        Map<String, Object> output =
                Map.of("$schema", ToolSchema.DRAFT_2020_12, "type", "object", "additionalProperties", false);
        return new ToolDefinition(
                name,
                new SemanticVersion("1.0.0"),
                TrustedSkillScriptToolProvider.PROVIDER_ID,
                "Entry fixture",
                "Executes the frozen package entry file",
                new ToolSchema("fixture.entry.input", "1.0.0", input),
                new ToolSchema("fixture.entry.output", "1.0.0", output),
                ToolExecutionMode.HOST_PROCESS,
                true,
                Duration.ofSeconds(5),
                "fixture",
                ToolIdempotency.NON_IDEMPOTENT,
                ToolRisk.HIGH,
                Set.of(ToolSideEffect.PROCESS_EXECUTION),
                new ToolResourceRequirements(
                        Set.of("execution_run"), Set.of(), Set.of(executionDigest, "sandbox@" + sandboxDigest)),
                List.of(),
                ToolApprovalRequirement.ALWAYS,
                "test",
                false,
                Set.of());
    }

    private static ToolInvocationRequest invocation(FrozenToolBinding binding) {
        return new ToolInvocationRequest(
                binding,
                new ToolCallId("entry-call-1"),
                new AgentRunId("entry-run-1"),
                new TenantRef("public"),
                new PrincipalRef("default-public-user", "user"),
                new ToolArguments("fixture.entry.input", "1.0.0", Map.of("value", "MODEL_VALUE")),
                NOW.plus(Duration.ofMinutes(2)),
                Optional.of("entry-invocation-1"),
                () -> false,
                Map.of(),
                ToolInvocationObserver.noop());
    }

    private static byte[] bytesOf(List<TrustedSkillScriptMaterialization.PackageFile> files, String relativePath) {
        return files.stream()
                .filter(file -> file.relativePath().equals(relativePath))
                .map(TrustedSkillScriptMaterialization.PackageFile::content)
                .findFirst()
                .orElseThrow();
    }

    /** Records stage/release proof for entry-file execution. */
    private static final class RecordingMaterialization implements TrustedSkillScriptMaterialization {
        final List<TrustedSkillScriptMaterialization.PackageFile> filesReceived = new ArrayList<>();
        final AtomicReference<StagedSkillPackage> staged = new AtomicReference<>();
        final AtomicInteger released = new AtomicInteger();

        @Override
        public StagedSkillPackage stage(String key, List<PackageFile> files, String entryRelativePath) {
            TrustedSkillScriptMaterialization.requireTrustedKey(key);
            filesReceived.clear();
            filesReceived.addAll(files);
            StagedSkillPackage value = new StagedSkillPackage(
                    key,
                    ".haifa-trusted-skills/" + key + "/" + entryRelativePath,
                    files.stream().map(PackageFile::relativePath).toList());
            staged.set(value);
            return value;
        }

        @Override
        public void release(StagedSkillPackage value) {
            released.incrementAndGet();
        }
    }
}

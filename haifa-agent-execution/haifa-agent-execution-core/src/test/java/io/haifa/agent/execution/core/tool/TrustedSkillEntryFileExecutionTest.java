package io.haifa.agent.execution.core.tool;

import static io.haifa.agent.execution.core.tool.ExecutionToolTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.execution.api.ExecutionBroker;
import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.ExecutionInput;
import io.haifa.agent.execution.api.ExecutionRequest;
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
import io.haifa.agent.tool.api.ToolName;
import io.haifa.agent.tool.api.ToolResourceRequirements;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSchema;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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

    @Test
    void preparesTheFrozenPackageOnceAndExecutesTheEntryFileByPath() {
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

        ToolResult first = fixture.provider().invoke(invocation(fixture.binding()));
        ToolResult second = fixture.provider().invoke(invocation(fixture.binding()));

        assertThat(first.successful()).isTrue();
        assertThat(second.successful()).isTrue();
        // One prepared version directory: bytes are loaded exactly once and reused afterwards.
        assertThat(materialization.loads.get()).isEqualTo(1);
        assertThat(materialization.prepared.get().versionKey()).matches("[0-9a-f]{64}");
        assertThat(materialization.prepared.get().workspaceRelativeEntryPath())
                .startsWith(".haifa-trusted-skills/")
                .endsWith("/scripts/main");
        assertThat(materialization.filesReceived)
                .extracting(TrustedSkillScriptPackageDirectory.PackageFile::relativePath)
                .containsExactlyInAnyOrder("SKILL.md", "helpers/util.txt", "scripts/main");
        assertThat(TrustedSkillEntryFileExecutionTest.bytesOf(materialization.filesReceived, "scripts/main"))
                .isEqualTo(ENTRY);
        assertThat(TrustedSkillEntryFileExecutionTest.bytesOf(materialization.filesReceived, "helpers/util.txt"))
                .isEqualTo(HELPER);
        // The entry file is executed by Workspace-relative path with empty standard input.
        ExecutionRequest request = captured.get();
        assertThat(request.command().argv())
                .containsExactly(
                        "trusted-runtime", materialization.prepared.get().workspaceRelativeEntryPath(), "MODEL_VALUE");
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
        assertThat(materialization.prepared.get()).isNull();
        assertThat(materialization.loads.get()).isEqualTo(1);
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
        assertThat(materialization.prepared.get()).isNull();
        assertThat(captured.get()).isNull();
    }

    @Test
    void keepsThePreparedVersionDirectoryWhenExecutionFails() {
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
        // The prepared version directory was not released with the failed invocation.
        assertThat(materialization.prepared.get()).isNotNull();
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
        assertThat(materialization.prepared.get()).isNull();
        assertThat(materialization.loads.get()).isEqualTo(0);
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

    /**
     * The frozen Skill execution-profile reference distinguishes two different package versions.
     */
    @Test
    void differentSkillPackageVersionsProduceDifferentToolIdentities() {
        byte[] updatedEntry = "NEW ENTRY".getBytes(StandardCharsets.UTF_8);
        SkillContentDigest updatedDigest = SkillTrustDigests.content(updatedEntry);
        assertThat(updatedDigest).isNotEqualTo(SkillTrustDigests.content(ENTRY));

        // Two different Skill package versions must never share a frozen identity reference.
        String original = TrustedSkillScriptToolSpec.skillProfileReference(
                binding(),
                "scripts/main",
                SkillTrustDigests.content(ENTRY),
                "fixture-runtime",
                Duration.ofSeconds(5),
                "entry fixture");
        String updated = TrustedSkillScriptToolSpec.skillProfileReference(
                binding(), "scripts/main", updatedDigest, "fixture-runtime", Duration.ofSeconds(5), "entry fixture");
        assertThat(updated).isNotEqualTo(original);
    }

    /**
     * R7: with the same package, entry and platform runtime set, changing the declared selected
     * runtime, the actual script timeout or the approval purpose changes the Tool identity - the
     * actual execution declaration cannot silently alias an old frozen Tool.
     */
    @Test
    void selectedRuntimeTimeoutAndPurposeChangeTheToolIdentity() {
        Duration timeout = Duration.ofSeconds(5);
        String original = TrustedSkillScriptToolSpec.skillProfileReference(
                binding(),
                "scripts/main",
                SkillTrustDigests.content(ENTRY),
                "fixture-runtime",
                timeout,
                "entry fixture");

        assertThat(TrustedSkillScriptToolSpec.skillProfileReference(
                        binding(),
                        "scripts/main",
                        SkillTrustDigests.content(ENTRY),
                        "other-runtime",
                        timeout,
                        "entry fixture"))
                .isNotEqualTo(original);
        assertThat(TrustedSkillScriptToolSpec.skillProfileReference(
                        binding(),
                        "scripts/main",
                        SkillTrustDigests.content(ENTRY),
                        "fixture-runtime",
                        timeout.plusMillis(1),
                        "entry fixture"))
                .isNotEqualTo(original);
        assertThat(TrustedSkillScriptToolSpec.skillProfileReference(
                        binding(),
                        "scripts/main",
                        SkillTrustDigests.content(ENTRY),
                        "fixture-runtime",
                        timeout,
                        "changed purpose"))
                .isNotEqualTo(original);
        // Trimmed purpose/runtime are one identity, not two.
        assertThat(TrustedSkillScriptToolSpec.skillProfileReference(
                        binding(),
                        "scripts/main",
                        SkillTrustDigests.content(ENTRY),
                        " fixture-runtime ",
                        timeout,
                        " entry fixture "))
                .isEqualTo(original);
    }

    /**
     * R7 recovery contract: an old frozen definition identity (computed for the declared facts of
     * an earlier configuration) can never validate a spec whose actual execution facts changed -
     * the mismatch fails closed in the existing spec validation instead of silently matching.
     */
    @Test
    void aStaleIdentityCannotMatchChangedExecutionDeclarations() {
        Duration timeout = Duration.ofSeconds(5);
        String staleIdentity = TrustedSkillScriptToolSpec.skillProfileReference(
                binding(),
                "scripts/main",
                SkillTrustDigests.content(ENTRY),
                "fixture-runtime",
                timeout,
                "entry fixture");
        ToolDefinition staleDefinition =
                definition(List.of("d".repeat(64), "sandbox@sha256:" + "a".repeat(64), staleIdentity));

        assertThatThrownBy(() -> new TrustedSkillScriptToolSpec(
                        staleDefinition,
                        binding(),
                        "scripts/main",
                        SkillTrustDigests.content(ENTRY),
                        "fixture-runtime",
                        "d".repeat(64),
                        "sha256:" + "a".repeat(64),
                        Set.of("execution_run"),
                        timeout,
                        "changed purpose",
                        arguments -> TrustedScriptArguments.atWorkspaceRoot(List.of()),
                        true))
                .isInstanceOf(IllegalArgumentException.class);
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
        ToolDefinition definition = definition(List.of(
                executionDigest,
                "sandbox@" + sandboxDigest,
                TrustedSkillScriptToolSpec.skillProfileReference(
                        binding(),
                        "scripts/main",
                        SkillTrustDigests.content(ENTRY),
                        "fixture-runtime",
                        Duration.ofSeconds(5),
                        "entry fixture")));
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
        return provider(broker, capabilities, validator, entryFileAdapter ? ENTRY_ADAPTER : stdinRuntime());
    }

    private static ExecutionBroker brokerCapturing(AtomicReference<ExecutionRequest> captured) {
        return broker(
                request -> {
                    captured.set(request);
                    return exited(request.id(), 0);
                },
                false);
    }

    private static ExecutionBroker brokerFailing() {
        return broker(
                request -> {
                    throw new IllegalStateException("execution survived failure");
                },
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

    private static ToolDefinition definition(List<String> executionProfiles) {
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
                new ToolResourceRequirements(Set.of("execution_run"), Set.of(), Set.copyOf(executionProfiles)),
                List.of(),
                ToolApprovalRequirement.ALWAYS,
                "test",
                false,
                Set.of());
    }

    private static byte[] bytesOf(List<TrustedSkillScriptPackageDirectory.PackageFile> files, String relativePath) {
        return files.stream()
                .filter(file -> file.relativePath().equals(relativePath))
                .map(TrustedSkillScriptPackageDirectory.PackageFile::content)
                .findFirst()
                .orElseThrow();
    }

    /**
     * Records the version-directory seam: received files, how often frozen bytes were loaded and
     * how often the prepared directory was reused without loading bytes again.
     */
    private static final class RecordingMaterialization implements TrustedSkillScriptPackageDirectory {
        final List<TrustedSkillScriptPackageDirectory.PackageFile> filesReceived = new ArrayList<>();
        final AtomicInteger loads = new AtomicInteger();
        final AtomicReference<PreparedSkillPackage> prepared = new AtomicReference<>();

        @Override
        public PreparedSkillPackage prepareIfAbsent(
                String key, java.util.function.Supplier<List<PackageFile>> files, String entryRelativePath) {
            TrustedSkillScriptPackageDirectory.requireVersionKey(key);
            PreparedSkillPackage existing = prepared.get();
            if (existing != null && existing.versionKey().equals(key)) return existing;
            loads.incrementAndGet();
            filesReceived.clear();
            filesReceived.addAll(files.get());
            PreparedSkillPackage value =
                    new PreparedSkillPackage(key, ".haifa-trusted-skills/" + key + "/" + entryRelativePath);
            prepared.set(value);
            return value;
        }
    }
}

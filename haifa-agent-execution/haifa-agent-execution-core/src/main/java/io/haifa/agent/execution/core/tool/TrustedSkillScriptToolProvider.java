package io.haifa.agent.execution.core.tool;

import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.skill.api.FrozenSkillBinding;
import io.haifa.agent.skill.api.SkillContentLoader;
import io.haifa.agent.skill.api.SkillResourceRef;
import io.haifa.agent.skill.api.SkillScope;
import io.haifa.agent.skill.api.SkillTrustDigests;
import io.haifa.agent.skill.api.SkillVisibilityContext;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolProvider;
import io.haifa.agent.tool.api.ToolProviderId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Provider for product-defined fixed Tools; it never accepts script identity or content from a
 * model. A spec either pipes one frozen script through standard input, or materializes the whole
 * frozen package below the Workspace root and executes the frozen entry file.
 */
public final class TrustedSkillScriptToolProvider implements ToolProvider {
    public static final ToolProviderId PROVIDER_ID = new ToolProviderId("haifa-trusted-skill-script");

    /** Total frozen package bytes one invocation may materialize. */
    static final long MAXIMUM_PACKAGE_BYTES = 16L * 1024 * 1024;

    private static final String MATERIALIZATION_NAMSPACE = "trusted-skill-script-materialization-v1";

    private final ExecutionToolProvider execution;
    private final SkillContentLoader contentLoader;
    private final boolean requiresMaterialization;
    private final TrustedSkillScriptMaterialization materialization;
    private final Map<String, TrustedSkillScriptToolSpec> specs;

    public TrustedSkillScriptToolProvider(
            ExecutionToolProvider execution, SkillContentLoader contentLoader, List<TrustedSkillScriptToolSpec> specs) {
        this(execution, contentLoader, null, specs);
    }

    public TrustedSkillScriptToolProvider(
            ExecutionToolProvider execution,
            SkillContentLoader contentLoader,
            TrustedSkillScriptMaterialization materialization,
            List<TrustedSkillScriptToolSpec> specs) {
        this.execution = Objects.requireNonNull(execution, "execution must not be null");
        this.contentLoader = Objects.requireNonNull(contentLoader, "contentLoader must not be null");
        Objects.requireNonNull(specs, "specs must not be null");
        Map<String, TrustedSkillScriptToolSpec> indexed = new LinkedHashMap<>();
        boolean anyEntryFile = false;
        for (TrustedSkillScriptToolSpec spec : specs) {
            String name = spec.definition().name().value();
            if (indexed.putIfAbsent(name, spec) != null) {
                throw new IllegalArgumentException("trusted script Tool names must be unique");
            }
            anyEntryFile = anyEntryFile || spec.entryFileExecution();
        }
        if (indexed.isEmpty()) throw new IllegalArgumentException("at least one trusted script Tool is required");
        this.requiresMaterialization = anyEntryFile;
        if (!anyEntryFile) {
            if (materialization != null) {
                throw new IllegalArgumentException(
                        "trusted script materialization is only declared with entry-file execution");
            }
            this.materialization = TrustedSkillScriptMaterialization.ofNothingStaged();
        } else if (materialization == null) {
            throw new IllegalArgumentException("entry-file trusted script Tools require a materialization seam");
        } else {
            this.materialization = Objects.requireNonNull(materialization, "materialization must not be null");
        }
        this.specs = Map.copyOf(indexed);
    }

    @Override
    public ToolProviderId id() {
        return PROVIDER_ID;
    }

    @Override
    public ToolResult invoke(ToolInvocationRequest invocation) {
        Objects.requireNonNull(invocation, "invocation must not be null");
        TrustedSkillScriptToolSpec spec =
                specs.get(invocation.binding().definition().name().value());
        if (spec == null
                || !spec.definition().equals(invocation.binding().definition())
                || !invocation
                        .binding()
                        .coordinate()
                        .name()
                        .equals(spec.definition().name())) {
            throw new SecurityException("trusted script Tool binding is unavailable or drifted");
        }
        if (!execution.configurationIdentity().equals(spec.executionConfigurationDigest())) {
            throw new SecurityException("trusted script execution configuration has drifted");
        }
        if (!SkillTrustDigests.sandbox(execution.sandboxProfileIdentity()).equals(spec.sandboxDigest())) {
            throw new SecurityException("trusted script sandbox profile has drifted");
        }
        var visibility = new SkillVisibilityContext(
                invocation.tenant(),
                invocation.principal(),
                Optional.empty(),
                false,
                Set.of(SkillScope.USER, SkillScope.PRODUCT));
        var content = contentLoader.load(spec.skillBinding(), visibility);
        if (!content.binding().equals(spec.skillBinding())) {
            throw new SecurityException("trusted script Skill binding has drifted");
        }
        TrustedScriptArguments mapped = Objects.requireNonNull(
                spec.argumentMapper().map(invocation.arguments().values()), "argument mapper must not return null");
        if (spec.entryFileExecution()) {
            return invokeEntryFile(invocation, spec, visibility, mapped);
        }
        String script = content.resource(spec.scriptRelativePath());
        if (!SkillTrustDigests.content(script).equals(spec.scriptDigest())) {
            throw new SecurityException("trusted script content has drifted");
        }
        return execution.invokeTrustedScript(
                invocation,
                spec.scriptRuntimeRef(),
                script,
                mapped.argv(),
                spec.purpose(),
                mapped.workingDirectory(),
                spec.timeout(),
                spec.requiredCapabilities(),
                mapped.workspaceInputPaths());
    }

    private ToolResult invokeEntryFile(
            ToolInvocationRequest invocation,
            TrustedSkillScriptToolSpec spec,
            SkillVisibilityContext visibility,
            TrustedScriptArguments mapped) {
        // Fail closed on the runtime capability before any package work happens.
        if (!execution.runtimeSupportsFileExecution(spec.scriptRuntimeRef())) {
            throw new IllegalArgumentException("trusted script runtime does not support entry-file execution");
        }
        List<TrustedSkillScriptMaterialization.PackageFile> files = frozenPackageFiles(spec.skillBinding(), visibility);
        String key = materializationKey(invocation, spec);
        var staged = materialization.stage(key, files, spec.scriptRelativePath());
        try {
            return execution.invokeTrustedPackageScript(
                    invocation,
                    spec.scriptRuntimeRef(),
                    staged.workspaceRelativeEntryPath(),
                    mapped.argv(),
                    spec.purpose(),
                    spec.timeout(),
                    spec.requiredCapabilities(),
                    mapped.workspaceInputPaths());
        } finally {
            materialization.release(staged);
        }
    }

    /** Loads every frozen package file as bytes and rejects any drift before placement. */
    private List<TrustedSkillScriptMaterialization.PackageFile> frozenPackageFiles(
            FrozenSkillBinding binding, SkillVisibilityContext visibility) {
        List<TrustedSkillScriptMaterialization.PackageFile> files = new ArrayList<>();
        long totalBytes = 0;
        for (SkillResourceRef resource : binding.packageIndex().resources()) {
            byte[] bytes = contentLoader
                    .loadResourceBytes(binding, visibility, resource.relativePath())
                    .orElseThrow(() -> new SecurityException("trusted script package bytes are unavailable"));
            if (bytes.length != resource.byteSize()
                    || !SkillTrustDigests.content(bytes).equals(resource.digest())) {
                throw new SecurityException("trusted script package content has drifted");
            }
            totalBytes += bytes.length;
            if (totalBytes > MAXIMUM_PACKAGE_BYTES) {
                throw new IllegalArgumentException("trusted script package exceeds the materialization budget");
            }
            files.add(new TrustedSkillScriptMaterialization.PackageFile(resource.relativePath(), bytes));
        }
        return files;
    }

    private static String materializationKey(ToolInvocationRequest invocation, TrustedSkillScriptToolSpec spec) {
        String digest = SkillTrustDigests.sha256(List.of(
                MATERIALIZATION_NAMSPACE,
                invocation.runId().value(),
                invocation.toolCallId().value(),
                spec.skillBinding().coordinate().externalForm(),
                spec.scriptDigest().value()));
        return digest.substring("sha256:".length());
    }
}

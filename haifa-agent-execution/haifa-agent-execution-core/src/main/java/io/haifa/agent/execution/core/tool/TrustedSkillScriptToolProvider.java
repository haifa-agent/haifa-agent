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
 * model. A spec either pipes one frozen script through standard input, or prepares the reusable
 * immutable directory of the frozen Skill package version once and executes the frozen entry file
 * of that prepared directory.
 */
public final class TrustedSkillScriptToolProvider implements ToolProvider {
    public static final ToolProviderId PROVIDER_ID = new ToolProviderId("haifa-trusted-skill-script");

    /** Total frozen package bytes one version preparation may load. */
    static final long MAXIMUM_PACKAGE_BYTES = 16L * 1024 * 1024;

    private static final String VERSION_KEY_NAMESPACE = "trusted-skill-script-package-directory-v1";

    private final ExecutionToolProvider execution;
    private final SkillContentLoader contentLoader;
    private final TrustedSkillScriptPackageDirectory packageDirectory;
    private final Map<String, TrustedSkillScriptToolSpec> specs;

    public TrustedSkillScriptToolProvider(
            ExecutionToolProvider execution, SkillContentLoader contentLoader, List<TrustedSkillScriptToolSpec> specs) {
        this(execution, contentLoader, null, specs);
    }

    public TrustedSkillScriptToolProvider(
            ExecutionToolProvider execution,
            SkillContentLoader contentLoader,
            TrustedSkillScriptPackageDirectory packageDirectory,
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
        if (!anyEntryFile) {
            if (packageDirectory != null) {
                throw new IllegalArgumentException(
                        "trusted Skill package directories are only declared with entry-file execution");
            }
            this.packageDirectory = TrustedSkillScriptPackageDirectory.unsupported();
        } else if (packageDirectory == null) {
            throw new IllegalArgumentException("entry-file trusted script Tools require a package directory seam");
        } else {
            this.packageDirectory = Objects.requireNonNull(packageDirectory, "packageDirectory must not be null");
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
        // The version directory is prepared once with byte-verified frozen bytes and reused for
        // every later invocation of the same Skill package version; entry-file identity therefore
        // holds (relative imports, entry detection) without per-call package copies.
        TrustedSkillScriptPackageDirectory.PreparedSkillPackage prepared = packageDirectory.prepareIfAbsent(
                versionKey(spec), () -> frozenPackageFiles(spec.skillBinding(), visibility), spec.scriptRelativePath());
        return execution.invokeTrustedPackageScript(
                invocation,
                spec.scriptRuntimeRef(),
                prepared.workspaceRelativeEntryPath(),
                mapped.argv(),
                spec.purpose(),
                spec.timeout(),
                spec.requiredCapabilities(),
                mapped.workspaceInputPaths());
    }

    /** Loads every frozen package file as bytes and rejects any drift before placement. */
    private List<TrustedSkillScriptPackageDirectory.PackageFile> frozenPackageFiles(
            FrozenSkillBinding binding, SkillVisibilityContext visibility) {
        List<TrustedSkillScriptPackageDirectory.PackageFile> files = new ArrayList<>();
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
                throw new IllegalArgumentException("trusted script package exceeds the preparation budget");
            }
            files.add(new TrustedSkillScriptPackageDirectory.PackageFile(resource.relativePath(), bytes));
        }
        return files;
    }

    /** Stable version-directory key of one frozen Skill package binding. */
    private static String versionKey(TrustedSkillScriptToolSpec spec) {
        return SkillTrustDigests.sha256(List.of(
                        VERSION_KEY_NAMESPACE,
                        spec.skillBinding().coordinate().externalForm(),
                        spec.skillBinding().registrationDigest().value()))
                .substring("sha256:".length());
    }
}

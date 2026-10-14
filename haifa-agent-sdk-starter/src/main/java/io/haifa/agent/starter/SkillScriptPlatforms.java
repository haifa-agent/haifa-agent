package io.haifa.agent.starter;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.id.UuidV7IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.execution.api.ExecutionEnvironmentRef;
import io.haifa.agent.execution.api.ExecutionOutputObserver;
import io.haifa.agent.execution.api.ExecutionScratchSpaceSpec;
import io.haifa.agent.execution.api.ResolvedExecutionEnvironment;
import io.haifa.agent.execution.api.ToolOutputPreviewPublisher;
import io.haifa.agent.execution.core.DefaultExecutionBroker;
import io.haifa.agent.execution.core.ImmutableSandboxProfileRegistry;
import io.haifa.agent.execution.core.ImmutableSandboxProviderRegistry;
import io.haifa.agent.execution.core.TransientToolOutputPreviewPublisher;
import io.haifa.agent.execution.core.store.InMemoryExecutionOutputStore;
import io.haifa.agent.execution.core.store.InMemoryExecutionStore;
import io.haifa.agent.execution.core.tool.ExecutionInvocationScopeResolver;
import io.haifa.agent.execution.core.tool.ExecutionToolConfiguration;
import io.haifa.agent.execution.core.tool.ExecutionToolDefinitionFactory;
import io.haifa.agent.execution.core.tool.ExecutionToolProvider;
import io.haifa.agent.execution.core.tool.ScriptRuntimeAdapter;
import io.haifa.agent.execution.core.tool.ScriptRuntimeResolver;
import io.haifa.agent.execution.core.tool.TrustedScriptArgumentMapper;
import io.haifa.agent.execution.core.tool.TrustedSkillScriptToolProvider;
import io.haifa.agent.execution.core.tool.TrustedSkillScriptToolSpec;
import io.haifa.agent.execution.host.tool.HostScriptRuntimeResolver;
import io.haifa.agent.project.core.store.InMemoryWorkspaceStore;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.hostworkspace.HostWorkspaceLocationStore;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import io.haifa.agent.runtime.api.ApprovalPrompt;
import io.haifa.agent.sandbox.api.SandboxConfigurationDigest;
import io.haifa.agent.sandbox.api.SandboxProfile;
import io.haifa.agent.sandbox.host.HostExecutionEnvironmentResolver;
import io.haifa.agent.sandbox.host.HostGuardedSandboxProvider;
import io.haifa.agent.sandbox.host.HostShell;
import io.haifa.agent.sandbox.host.ResolvedHostEnvironment;
import io.haifa.agent.sdk.api.HaifaAgentBuilder;
import io.haifa.agent.sdk.api.HaifaAgentException;
import io.haifa.agent.sdk.contribution.ProductApprovalPromptFormatter;
import io.haifa.agent.sdk.contribution.ToolRegistration;
import io.haifa.agent.skill.api.FrozenSkillBinding;
import io.haifa.agent.skill.api.SkillContentDigest;
import io.haifa.agent.skill.api.SkillContentLoader;
import io.haifa.agent.skill.api.SkillResourceKind;
import io.haifa.agent.skill.api.SkillResourceRef;
import io.haifa.agent.skill.api.SkillTrustDigests;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.SemanticVersion;
import io.haifa.agent.tool.api.ToolAlias;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolDefinition;
import io.haifa.agent.tool.api.ToolExecutionMode;
import io.haifa.agent.tool.api.ToolIdempotency;
import io.haifa.agent.tool.api.ToolName;
import io.haifa.agent.tool.api.ToolResourceRequirements;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSchema;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Assembles the trusted Skill script execution capability into any {@link HaifaAgentBuilder}.
 *
 * <p>The facade mirrors {@link HostExecutionPlatforms}: one static factory resolves every trusted
 * fact once and returns an owned contribution. It adds no second Broker, Sandbox, Policy or Runtime
 * implementation - it reuses the exact 038-30 assembly ({@link HostGuardedSandboxProvider} as the
 * only process owner, {@link DefaultExecutionBroker}, {@link FrozenHostExecutionPolicy} and
 * {@link ExecutionToolProvider}) with a frozen Skill script provider on top. Every generated Tool
 * keeps {@link ToolApprovalRequirement#ALWAYS} and a frozen {@link SkillContentDigest}, and a
 * declined approval never starts a process.
 *
 * <pre>{@code
 * var skills = SkillScriptPlatforms.trusted(
 *         new SkillScriptPlatforms.SkillScriptSpec(
 *                 workspaceRoot,
 *                 dataRoot,
 *                 List.of(new SkillScriptPlatforms.SkillScriptRuntime("bun", Path.of("C:/run/bun.exe"))),
 *                 skillContentLoader,
 *                 List.of(new SkillScriptPlatforms.SkillScriptToolDeclaration(
 *                         new ToolName("generate_image"),
 *                         "Generate one image",
 *                         "Generates one PNG image through the frozen skill package script.",
 *                         inputSchema,
 *                         Duration.ofMinutes(2),
 *                         frozenSkillBinding,
 *                         "scripts/main.ts",
 *                         "bun",
 *                         Duration.ofSeconds(120),
 *                         "generate a reviewed image artifact",
 *                         arguments -> TrustedScriptArguments.atWorkspaceRoot(List.of((String) arguments.get("prompt"))))),
 *                 Duration.ofSeconds(30),
 *                 Duration.ofMinutes(10),
 *                 1_048_576,
 *                 2_000),
 *         caller.tenant(),
 *         caller.principal());
 * skills.applyTo(builder);
 * }</pre>
 *
 * <p>The declared Tool runs one frozen multi-file Skill package: the platform prepares the
 * immutable execution directory of the frozen package version once below the Workspace root
 * (every frozen package byte is verified against the resource index on preparation; any drift is
 * rejected), reuses that directory on every later invocation of the same version, and executes the
 * declared entry file by path. Entry-file identity therefore holds: relative imports resolve and
 * entry detection works, which the standard-input model cannot express. Prepared directories are
 * never deleted per invocation.
 *
 * <p>Trusted runtimes are declared by the product with an explicit executable path and are
 * preflighted (existence, exact file, deadline-bounded version probe) at assembly; nothing is
 * guessed from the host {@code PATH} at runtime. Script output stays in the ordinary
 * {@code outputs/} directory below the Workspace root and the product's existing Artifact chain
 * publishes it - this entry adds no output capture, publication or deletion layer. The declared
 * Tool keeps the ordinary Runtime boundary for every invocation: one exact Approval, the frozen
 * invocation digest, capability {@code execution_run}, the canonical execution result schema, and
 * bounded, redacted output.
 */
public final class SkillScriptPlatforms {
    private static final String WORKSPACE_ID = "trusted-skill-script";
    private static final String PROJECT_ID = "trusted-skill-script";
    private static final String WORKSPACE_REVISION_DIGEST = "trusted-skill-script-v1";
    private static final String SANDBOX_PROFILE_ID = "trusted-skill-script-execution";
    private static final String PROFILE_POLICY_VERSION = "trusted-skill-script-entry-v1";
    private static final String BINDING_NAMESPACE = "io.haifa.agent.sdk.starter.trusted-skill-script/v1";
    private static final String BINDING_REFERENCE_PREFIX = "trusted-skill-script:v1:";
    private static final String SCRATCH_DIRECTORY_NAME = "trusted-skill-script-scratch";
    private static final String UNAVAILABLE_CODE = "SKILL_SCRIPT_UNAVAILABLE";
    private static final String RUNTIME_UNAVAILABLE_CODE = "SKILL_SCRIPT_RUNTIME_UNAVAILABLE";
    private static final int MAX_APPROVAL_VALUE_LENGTH = 512;
    private static final int MAX_APPROVAL_PROMPT_LENGTH = 4_000;
    private static final Set<String> CAPABILITIES = Set.of("execution_run");
    /** Languages the entry accepts as declared, trusted package runtimes. */
    public static final Set<String> SUPPORTED_LANGUAGES = Set.of("bash", "sh", "python", "bun", "node", "powershell");

    private SkillScriptPlatforms() {}

    /**
     * Resolves the trusted facts and returns the owned Skill script contribution for one builder.
     *
     * <p>Resolution is fail closed, with stable codes and before any Tool reaches a builder: a
     * declared tool whose runtime is not declared, a runtime executable that is not an explicit
     * existing file, a failed runtime probe, a declaration whose entry script is not an indexed
     * SCRIPT resource of its frozen Skill binding, and every preflight failure of the frozen
     * execution assembly throw {@link HaifaAgentException} with one of the assembly codes.
     */
    public static SkillScriptPlatform trusted(SkillScriptSpec spec, TenantRef tenant, PrincipalRef principal) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(tenant, "tenant must not be null");
        Objects.requireNonNull(principal, "principal must not be null");
        for (SkillScriptRuntime runtime : spec.runtimes()) {
            runtime.preflight();
        }
        String workspaceIdentity = HostExecutionPlatforms.workspaceIdentity(
                BINDING_NAMESPACE, WORKSPACE_ID, spec.workspaceRoot(), UNAVAILABLE_CODE);
        Path scratchRoot =
                HostExecutionPlatforms.scratchRoot(spec.dataRoot(), SCRATCH_DIRECTORY_NAME, UNAVAILABLE_CODE);
        IdentifierGenerator identifiers = new UuidV7IdentifierGenerator();
        TimeProvider time = Clock.systemUTC()::instant;
        var workspaces = new InMemoryWorkspaceStore();
        var locations = new HostWorkspaceLocationStore();
        WorkspaceId workspaceId = new WorkspaceId(WORKSPACE_ID);
        locations.register(workspaceId, spec.workspaceRoot());
        workspaces.create(Workspace.provision(
                        workspaceId,
                        new ProjectId(PROJECT_ID),
                        WorkspaceRevision.initial(WORKSPACE_REVISION_DIGEST + ":" + workspaceIdentity),
                        time.now())
                .activate(time.now()));
        HostShell shell;
        try {
            shell = HostShell.auto();
        } catch (IllegalStateException exception) {
            throw new HaifaAgentException(
                    UNAVAILABLE_CODE,
                    "product.assemble",
                    "execution",
                    "no supported host shell is available",
                    exception);
        }
        var host = new HostGuardedSandboxProvider(workspaces, locations, identifiers, time, shell, scratchRoot);
        ScriptRuntimeResolver runtimes = trustedRuntimes(spec.runtimes());
        for (SkillScriptToolDeclaration declaration : spec.tools()) {
            try {
                runtimes.resolve(declaration.scriptRuntimeRef());
            } catch (IllegalArgumentException exception) {
                throw undeclaredRuntime(declaration, exception);
            }
        }
        ResolvedHostEnvironment hostEnvironment = HostExecutionEnvironmentResolver.resolveHostUser(
                System.getenv(),
                System.getProperty("os.name", ""),
                Path.of(System.getProperty("user.home", ".")),
                spec.dataRoot(),
                spec.workspaceRoot(),
                scratchRoot,
                Set.of());
        SandboxProfile profile = HostExecutionPlatforms.sandboxProfile(
                host, runtimes, hostEnvironment.allowedEnvironmentNames(), SANDBOX_PROFILE_ID, PROFILE_POLICY_VERSION);
        HostExecutionPlatforms.preflight(host, profile, UNAVAILABLE_CODE);
        ToolOutputPreviewPublisher previewPublisher = new TransientToolOutputPreviewPublisher();
        ExecutionToolConfiguration configuration = new ExecutionToolConfiguration(
                new ExecutionEnvironmentRef(List.of(
                        SANDBOX_PROFILE_ID + "-" + profile.contentDigest().value())),
                profile.ref(),
                spec.defaultTimeout(),
                spec.maximumTimeout(),
                spec.maximumOutputBytes(),
                spec.maximumOutputLines(),
                Optional.empty(),
                false,
                runtimes,
                ExecutionOutputObserver.noop(),
                UnaryOperator.identity(),
                ExecutionScratchSpaceSpec.none(),
                previewPublisher);
        var broker = new DefaultExecutionBroker(
                new InMemoryExecutionStore(),
                new InMemoryExecutionOutputStore(),
                ignored -> ResolvedExecutionEnvironment.of(hostEnvironment.environment()),
                new FrozenHostExecutionPolicy(configuration, tenant, principal, workspaceId),
                new ImmutableSandboxProfileRegistry(List.of(profile)),
                new ImmutableSandboxProviderRegistry(List.of(host)),
                workspaces);
        var execution = new ExecutionToolProvider(
                broker,
                identifiers,
                time,
                ignored -> new ExecutionInvocationScopeResolver.ExecutionInvocationScope(workspaceId, CAPABILITIES),
                configuration);
        var access = new SkillScriptPlatformWorkspaceAccess(locations, workspaceId, UNAVAILABLE_CODE);
        var packageDirectory = new SkillScriptPackageDirectory(access);
        String executionDigest = execution.configurationIdentity();
        String sandboxDigest = SkillTrustDigests.sandbox(execution.sandboxProfileIdentity());
        List<TrustedSkillScriptToolSpec> innerSpecs = new ArrayList<>();
        List<ToolDefinition> definitions = new ArrayList<>();
        List<String> toolNames = new ArrayList<>();
        Map<String, PromptFacts> promptFacts = new LinkedHashMap<>();
        for (SkillScriptToolDeclaration declaration : spec.tools()) {
            ToolDefinition definition = declaration.toDefinition(executionDigest, sandboxDigest);
            innerSpecs.add(new TrustedSkillScriptToolSpec(
                    definition,
                    declaration.skillBinding(),
                    declaration.scriptRelativePath(),
                    declaration.scriptDigest(),
                    declaration.scriptRuntimeRef(),
                    executionDigest,
                    sandboxDigest,
                    CAPABILITIES,
                    declaration.timeout(),
                    declaration.purpose(),
                    declaration.argumentMapper(),
                    true));
            definitions.add(definition);
            toolNames.add(definition.name().value());
            promptFacts.put(
                    definition.name().value(),
                    new PromptFacts(
                            declaration.scriptRelativePath(),
                            declaration.scriptRuntimeRef(),
                            declaration.timeout(),
                            declaration.purpose()));
        }
        TrustedSkillScriptToolProvider trustedProvider =
                new TrustedSkillScriptToolProvider(execution, spec.skillContentLoader(), packageDirectory, innerSpecs);
        List<ToolRegistration> wired = definitions.stream()
                .map(definition -> new ToolRegistration(
                        new ToolAlias(definition.name().value()),
                        definition,
                        BINDING_REFERENCE_PREFIX + workspaceIdentity,
                        trustedProvider))
                .toList();
        String profileIdentity = profile.ref().value() + "@" + profile.ref().version() + "+" + workspaceIdentity;
        return new SkillScriptedPlatform(
                wired,
                List.copyOf(toolNames),
                promptFacts,
                previewPublisher,
                shell.displayName(),
                profileIdentity,
                runtimes.fileExecutionLanguages());
    }

    private static HaifaAgentException undeclaredRuntime(
            SkillScriptToolDeclaration declaration, IllegalArgumentException exception) {
        return new HaifaAgentException(
                RUNTIME_UNAVAILABLE_CODE,
                "product.assemble",
                "execution",
                "trusted script Tool "
                        + declaration.name().value()
                        + " declares an undeclared runtime;"
                        + " declare the runtime executable on the platform before using it as a Tool",
                exception);
    }

    /** One product-declared trusted package runtime with its explicit executable. */
    public record SkillScriptRuntime(String language, Path executable) {
        public SkillScriptRuntime {
            Objects.requireNonNull(language, "language must not be null");
            Objects.requireNonNull(executable, "executable must not be null");
        }

        /** Existence, exact file and deadline-bounded version probe; nothing is guessed from PATH. */
        void preflight() {
            preflight(Duration.ofSeconds(15));
        }

        void preflight(Duration probeDeadline) {
            String normalized = language.trim().toLowerCase(Locale.ROOT);
            if (!SkillScriptPlatforms.SUPPORTED_LANGUAGES.contains(normalized)) {
                throw new HaifaAgentException(
                        SkillScriptPlatforms.RUNTIME_UNAVAILABLE_CODE,
                        "product.assemble",
                        "execution",
                        "trusted script runtime language " + normalized + " is not supported by this entry");
            }
            if (!executable.isAbsolute()) {
                throw new HaifaAgentException(
                        SkillScriptPlatforms.RUNTIME_UNAVAILABLE_CODE,
                        "product.assemble",
                        "execution",
                        "trusted script runtime executable must be an explicit path, not a host PATH name");
            }
            if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)) {
                throw new HaifaAgentException(
                        SkillScriptPlatforms.RUNTIME_UNAVAILABLE_CODE,
                        "product.assemble",
                        "execution",
                        "trusted script runtime executable does not exist as an exact file");
            }
            List<String> probe = SkillScriptPlatforms.probeArguments(normalized);
            if (probe.isEmpty()) return;
            runProbe(probe, SkillScriptPlatforms.probeExpectsOutput(normalized), probeDeadline);
        }

        /**
         * One shared bounded deadline covers merged output consumption and process waiting: both
         * streams are drained on one merged pipe (a filled stderr can never deadlock the probe),
         * only the first probe output bound is retained, and a run that does not exit within the
         * deadline - or a probe that is interrupted - is destroyed and reaped. No budget is added
         * and there is no retry.
         */
        private void runProbe(List<String> arguments, boolean expectsOutput, Duration deadline) {
            final int retainedOutputBytes = 4_096;
            byte[] probeOutput = new byte[] {};
            Process process = start(arguments);
            try {
                var merged = new java.io.ByteArrayOutputStream();
                Thread drain = new Thread(() -> drain(process, merged, retainedOutputBytes));
                drain.setDaemon(true);
                drain.start();
                long deadlineNanos = System.nanoTime() + deadline.toNanos();
                long remainingNanos = deadlineNanos - System.nanoTime();
                boolean exited = remainingNanos > 0 && process.waitFor(remainingNanos, TimeUnit.NANOSECONDS);
                if (!exited) {
                    destroy(process);
                    throw unavailable("trusted script runtime probe did not exit within its deadline");
                }
                drain.join(TimeUnit.SECONDS.toMillis(1));
                synchronized (merged) {
                    probeOutput = merged.toByteArray();
                }
                destroy(process);
                if (process.exitValue() != 0 || (expectsOutput && probeOutput.length == 0)) {
                    throw unavailable("trusted script runtime probe failed for language " + normalizedLanguage());
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                destroy(process);
                throw new HaifaAgentException(
                        SkillScriptPlatforms.RUNTIME_UNAVAILABLE_CODE,
                        "product.assemble",
                        "execution",
                        "trusted script runtime probe was interrupted",
                        exception);
            }
        }

        private void drain(Process process, java.io.ByteArrayOutputStream merged, int retainedOutputBytes) {
            byte[] buffer = new byte[512];
            int retained = 0;
            // Draining runs to EOF so the merged pipe can never fill and block the child, while
            // only the first bound of bytes is retained for the version diagnostic check.
            try (var stream = process.getInputStream()) {
                int read;
                while ((read = stream.read(buffer)) >= 0) {
                    synchronized (merged) {
                        int copy = Math.min(read, Math.max(0, retainedOutputBytes - merged.size()));
                        if (copy > 0) merged.write(buffer, 0, copy);
                        retained += read;
                    }
                }
            } catch (IOException ignored) {
                // The child was destroyed or its pipe was closed; the deadline decides the outcome.
            }
        }

        private Process start(List<String> arguments) {
            try {
                return new ProcessBuilder(Stream.concat(Stream.of(executable.toString()), arguments.stream())
                                .toList())
                        .redirectErrorStream(true)
                        .start();
            } catch (IOException exception) {
                throw new HaifaAgentException(
                        SkillScriptPlatforms.RUNTIME_UNAVAILABLE_CODE,
                        "product.assemble",
                        "execution",
                        "trusted script runtime executable cannot be launched",
                        exception);
            }
        }

        private static void destroy(Process process) {
            if (!process.isAlive()) return;
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(1, TimeUnit.SECONDS);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }

        private HaifaAgentException unavailable(String message) {
            return new HaifaAgentException(
                    SkillScriptPlatforms.RUNTIME_UNAVAILABLE_CODE, "product.assemble", "execution", message);
        }

        private String normalizedLanguage() {
            return language.trim().toLowerCase(Locale.ROOT);
        }
    }

    static List<String> probeArguments(String language) {
        return switch (language) {
            case "bun", "python", "node", "bash" -> List.of("--version");
            case "sh" -> List.of("-c", "exit 0");
            case "powershell" -> List.of("-NoLogo", "-NoProfile", "-NonInteractive", "-Command", "exit 0");
            default -> List.of();
        };
    }

    /** Version probes must print diagnostics; command-only probes only need a clean exit. */
    static boolean probeExpectsOutput(String language) {
        return switch (language) {
            case "bun", "python", "node", "bash" -> true;
            default -> false;
        };
    }

    private static ScriptRuntimeResolver trustedRuntimes(List<SkillScriptRuntime> runtimes) {
        List<ScriptRuntimeAdapter> adapters = new ArrayList<>();
        for (SkillScriptRuntime runtime : runtimes) {
            String language = runtime.language().trim().toLowerCase(Locale.ROOT);
            String executable = runtime.executable().toString();
            adapters.add(
                    switch (language) {
                        case "bun" -> ScriptRuntimeResolver.bun(executable);
                        case "bash" -> ScriptRuntimeResolver.bashFile(executable);
                        case "sh" -> ScriptRuntimeResolver.trustedPackageRuntime("sh", executable, List.of());
                        case "python" -> ScriptRuntimeResolver.pythonFile(executable);
                        case "node" -> ScriptRuntimeResolver.trustedPackageRuntime("node", executable, List.of());
                        case "powershell" -> ScriptRuntimeResolver.powerShellFile(executable);
                        default ->
                            throw new HaifaAgentException(
                                    RUNTIME_UNAVAILABLE_CODE,
                                    "product.assemble",
                                    "execution",
                                    "trusted script runtime language " + language + " is not supported by this entry");
                    });
        }
        return new ScriptRuntimeResolver(HostScriptRuntimeResolver.currentOperatingSystem(), adapters);
    }

    /**
     * Product declaration of one fixed business Tool backed by the frozen entry script of one
     * frozen Skill package. Inputs are trusted facts; the model supplies only the mapped business
     * arguments, and every other script identity stays frozen. The Tool result contract is the
     * canonical execution result schema of the shared Execution module, so the declared schema
     * always accepts the envelope the Runtime actually returns.
     */
    public record SkillScriptToolDeclaration(
            ToolName name,
            String title,
            String description,
            ToolSchema inputSchema,
            Duration definitionTimeout,
            FrozenSkillBinding skillBinding,
            String scriptRelativePath,
            String scriptRuntimeRef,
            Duration timeout,
            String purpose,
            TrustedScriptArgumentMapper argumentMapper) {
        public SkillScriptToolDeclaration {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(title, "title must not be null");
            Objects.requireNonNull(description, "description must not be null");
            Objects.requireNonNull(inputSchema, "inputSchema must not be null");
            definitionTimeout = Objects.requireNonNull(definitionTimeout, "definitionTimeout must not be null");
            Objects.requireNonNull(skillBinding, "skillBinding must not be null");
            scriptRelativePath = Objects.requireNonNull(scriptRelativePath, "scriptRelativePath must not be null")
                    .trim()
                    .replace('\\', '/');
            Objects.requireNonNull(scriptRuntimeRef, "scriptRuntimeRef must not be null");
            Objects.requireNonNull(timeout, "timeout must not be null");
            Objects.requireNonNull(purpose, "purpose must not be null");
            Objects.requireNonNull(argumentMapper, "argumentMapper must not be null");
        }

        SkillContentDigest scriptDigest() {
            return skillBinding.packageIndex().resources().stream()
                    .filter(resource -> resource.kind() == SkillResourceKind.SCRIPT
                            && resource.relativePath().equals(scriptRelativePath))
                    .map(SkillResourceRef::digest)
                    .findFirst()
                    .orElseThrow(() -> new HaifaAgentException(
                            UNAVAILABLE_CODE,
                            "product.assemble",
                            "execution",
                            "the declared entry script is not an indexed SCRIPT resource of the frozen Skill binding"));
        }

        ToolDefinition toDefinition(String executionConfigurationDigest, String sandboxDigest) {
            return new ToolDefinition(
                    name,
                    new SemanticVersion("1.0.0"),
                    TrustedSkillScriptToolProvider.PROVIDER_ID,
                    title,
                    description,
                    inputSchema,
                    ExecutionToolDefinitionFactory.executionResultSchema(),
                    ToolExecutionMode.HOST_PROCESS,
                    true,
                    definitionTimeout,
                    "per-workspace-execution",
                    ToolIdempotency.NON_IDEMPOTENT,
                    ToolRisk.HIGH,
                    Set.of(ToolSideEffect.PROCESS_EXECUTION, ToolSideEffect.NETWORK_ACCESS),
                    new ToolResourceRequirements(
                            Set.of("execution_run"),
                            Set.of("unrestricted-network"),
                            Set.of(
                                    executionConfigurationDigest,
                                    "sandbox@" + sandboxDigest,
                                    TrustedSkillScriptToolSpec.skillProfileReference(
                                            skillBinding, scriptRelativePath, scriptDigest()))),
                    List.of(),
                    ToolApprovalRequirement.ALWAYS,
                    "io.haifa.agent.sdk.starter",
                    false,
                    Set.of("skill", "script"));
        }
    }

    /** Product declaration of one trusted Skill script platform. */
    public record SkillScriptSpec(
            Path workspaceRoot,
            Path dataRoot,
            List<SkillScriptRuntime> runtimes,
            SkillContentLoader skillContentLoader,
            List<SkillScriptToolDeclaration> tools,
            Duration defaultTimeout,
            Duration maximumTimeout,
            int maximumOutputBytes,
            int maximumOutputLines) {
        public SkillScriptSpec {
            workspaceRoot = HostExecutionPlatforms.hostDirectory(workspaceRoot, "workspaceRoot");
            dataRoot = Objects.requireNonNull(dataRoot, "dataRoot must not be null")
                    .toAbsolutePath()
                    .normalize();
            runtimes = List.copyOf(Objects.requireNonNull(runtimes, "runtimes must not be null"));
            if (runtimes.isEmpty()) {
                throw new IllegalArgumentException("at least one trusted script runtime is required");
            }
            for (SkillScriptRuntime runtime : runtimes) {
                if (!SUPPORTED_LANGUAGES.contains(runtime.language().trim().toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("trusted script runtime language is not supported");
                }
            }
            skillContentLoader = Objects.requireNonNull(skillContentLoader, "skillContentLoader must not be null");
            tools = List.copyOf(Objects.requireNonNull(tools, "tools must not be null"));
            if (tools.isEmpty()) {
                throw new IllegalArgumentException("at least one trusted script Tool declaration is required");
            }
            defaultTimeout = Objects.requireNonNull(defaultTimeout, "defaultTimeout must not be null");
            maximumTimeout = Objects.requireNonNull(maximumTimeout, "maximumTimeout must not be null");
        }
    }

    /**
     * One owned trusted Skill script contribution with the frozen Tool registrations, builder
     * wiring and the process-local output preview publisher of the same platform.
     *
     * <p>Call {@link #applyTo} at most once. When applied, the platform registers itself as a
     * managed resource, so an Agent that builds successfully closes it on
     * {@link io.haifa.agent.sdk.api.HaifaAgent#close()}. A caller that abandons the build before
     * the Agent adopts the resource must {@link #close()} it explicitly.
     */
    public interface SkillScriptPlatform extends AutoCloseable {
        /** Returns the immutable registered Tool names of this contribution. */
        Set<String> toolNames();

        /**
         * Returns the exact reviewed Tool registrations this contribution hands to a builder. A
         * product that owns its own Tool platform registers these values on that platform instead
         * of calling {@link #applyTo}; the registrations are immutable and may be read after close.
         */
        List<ToolRegistration> registrations();

        /** Returns the process-local publisher of bounded Tool output previews of this platform. */
        ToolOutputPreviewPublisher previewPublisher();

        /** Trusted, non-secret description of the resolved host and sandbox profile for diagnostics. */
        String securitySummary();

        /** Returns the Skill-script-aware Approval prompt formatter of this platform. */
        ProductApprovalPromptFormatter approvalPromptFormatter();

        /**
         * Wires the frozen Tool registrations, the Skill-script-aware Approval prompt and the
         * managed resource into a builder.
         *
         * @throws IllegalStateException when called more than once
         */
        void applyTo(HaifaAgentBuilder builder);

        /** Releases everything this contribution owns. Idempotent. */
        @Override
        void close();
    }

    private record PromptFacts(String scriptRelativePath, String scriptRuntimeRef, Duration timeout, String purpose) {}

    private static final class SkillScriptedPlatform implements SkillScriptPlatform {
        private final List<ToolRegistration> registrations;
        private final List<String> toolNames;
        private final Map<String, PromptFacts> promptFacts;
        private final ToolOutputPreviewPublisher previewPublisher;
        private final String shellDisplayName;
        private final String profileIdentity;
        private final Set<String> runtimeLanguages;
        private final AtomicBoolean applied = new AtomicBoolean();

        private SkillScriptedPlatform(
                List<ToolRegistration> registrations,
                List<String> toolNames,
                Map<String, PromptFacts> promptFacts,
                ToolOutputPreviewPublisher previewPublisher,
                String shellDisplayName,
                String profileIdentity,
                Set<String> runtimeLanguages) {
            this.registrations = Objects.requireNonNull(registrations, "registrations must not be null");
            this.toolNames = Objects.requireNonNull(toolNames, "toolNames must not be null");
            this.promptFacts = Map.copyOf(Objects.requireNonNull(promptFacts, "promptFacts must not be null"));
            this.previewPublisher = Objects.requireNonNull(previewPublisher, "previewPublisher must not be null");
            this.shellDisplayName = Objects.requireNonNull(shellDisplayName, "shellDisplayName must not be null");
            this.profileIdentity = Objects.requireNonNull(profileIdentity, "profileIdentity must not be null");
            this.runtimeLanguages =
                    Set.copyOf(Objects.requireNonNull(runtimeLanguages, "runtimeLanguages must not be null"));
        }

        @Override
        public Set<String> toolNames() {
            return Set.copyOf(toolNames);
        }

        @Override
        public List<ToolRegistration> registrations() {
            return registrations;
        }

        @Override
        public ToolOutputPreviewPublisher previewPublisher() {
            return previewPublisher;
        }

        @Override
        public String securitySummary() {
            return "trusted-skill-script (current OS user, no strong isolation), shell="
                    + shellDisplayName
                    + ", runtimes=" + runtimeLanguages.stream().sorted().collect(Collectors.joining(","))
                    + ", network=host (ordinary local network, not enforced by this sandbox)"
                    + ", profile=" + profileIdentity;
        }

        @Override
        public void applyTo(HaifaAgentBuilder builder) {
            Objects.requireNonNull(builder, "builder must not be null");
            if (!applied.compareAndSet(false, true)) {
                throw new IllegalStateException("trusted skill script platform is already applied to a builder");
            }
            builder.toolRegistrations(registrations)
                    .managedResource(this)
                    .toolApprovalPrompts(approvalPromptFormatter());
        }

        @Override
        public ProductApprovalPromptFormatter approvalPromptFormatter() {
            return this::approvalPrompt;
        }

        /** Presents exactly what one approved invocation executes, with its frozen script facts. */
        private ApprovalPrompt approvalPrompt(FrozenToolBinding binding, ToolCall call, boolean reauthentication) {
            String toolName = binding.definition().name().value();
            PromptFacts facts = promptFacts.get(toolName);
            if (facts == null
                    || !TrustedSkillScriptToolProvider.PROVIDER_ID
                            .value()
                            .equals(binding.definition().providerId().value())) {
                return ProductApprovalPromptFormatter.defaultFormatter().format(binding, call, reauthentication);
            }
            String arguments = call.arguments().values().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> entry.getKey() + "=" + approvalValue(entry.getValue()))
                    .collect(Collectors.joining("\n  "));
            String digest = SandboxConfigurationDigest.sha256Fields(List.of(
                            toolName, String.valueOf(call.arguments().values().hashCode())))
                    .value();
            String summary = (reauthentication
                            ? "Reauthenticate and approve trusted skill script"
                            : "Approve trusted skill script")
                    + "\nTool: " + toolName
                    + "\nScript: " + facts.scriptRelativePath
                    + " (frozen Skill package, entry-file execution)"
                    + "\nRuntime: " + facts.scriptRuntimeRef
                    + "\nPurpose: " + facts.purpose
                    + "\nArguments:\n  " + (arguments.isBlank() ? "(none)" : arguments)
                    + "\nTimeout: " + facts.timeout.toMillis() + " ms"
                    + "\nOutput: bounded and redacted; script files stay under the workspace"
                    + " outputs/ directory and the product's existing artifact chain publishes them"
                    + "\nWorkspace: the single registered host directory; the model cannot select the"
                    + " working directory"
                    + "\nProvider: trusted host process as the current OS user, no strong isolation"
                    + "\nNetwork: ordinary host network; this sandbox cannot deny it"
                    + "\nInvocation digest: " + digest
                    + "\nRisks: HIGH, PROCESS_EXECUTION, NETWORK_ACCESS, NON_IDEMPOTENT, host file access";
            return ApprovalPrompt.of(HostExecutionPlatforms.bounded(summary, MAX_APPROVAL_PROMPT_LENGTH));
        }

        private static String approvalValue(Object value) {
            if (value == null) return "(none)";
            if (value instanceof List<?> list) {
                return list.isEmpty()
                        ? "(none)"
                        : HostExecutionPlatforms.bounded(
                                list.stream().map(String::valueOf).collect(Collectors.joining(" ")),
                                MAX_APPROVAL_VALUE_LENGTH);
            }
            return HostExecutionPlatforms.bounded(String.valueOf(value).strip(), MAX_APPROVAL_VALUE_LENGTH);
        }

        /**
         * Releases nothing today: every process lease is opened and closed per invocation, and the
         * prepared Skill package directories persist below their workspace for reuse, exactly like
         * the host execution entry. The method exists so a caller that abandons a build releases
         * the contribution.
         */
        @Override
        public void close() {}
    }
}

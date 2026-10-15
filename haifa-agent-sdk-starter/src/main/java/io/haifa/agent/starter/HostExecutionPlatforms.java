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
import io.haifa.agent.execution.api.SandboxProfileRef;
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
import io.haifa.agent.execution.core.tool.ScriptRuntimeResolver;
import io.haifa.agent.execution.host.tool.HostScriptRuntimeResolver;
import io.haifa.agent.project.core.store.InMemoryWorkspaceStore;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.hostworkspace.HostWorkspaceLocationStore;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import io.haifa.agent.runtime.api.ApprovalPrompt;
import io.haifa.agent.sandbox.api.SandboxConfigurationDigest;
import io.haifa.agent.sandbox.api.SandboxException;
import io.haifa.agent.sandbox.api.SandboxProfile;
import io.haifa.agent.sandbox.host.HostExecutionEnvironmentResolver;
import io.haifa.agent.sandbox.host.HostGuardedSandboxProvider;
import io.haifa.agent.sandbox.host.HostShell;
import io.haifa.agent.sandbox.host.ResolvedHostEnvironment;
import io.haifa.agent.sdk.api.HaifaAgentBuilder;
import io.haifa.agent.sdk.api.HaifaAgentException;
import io.haifa.agent.sdk.contribution.ProductApprovalPromptFormatter;
import io.haifa.agent.sdk.contribution.ToolRegistration;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.ToolDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Assembles the trusted host execution capability into any {@link HaifaAgentBuilder}.
 *
 * <p>The facade mirrors {@link McpToolPlatforms}: one static factory resolves every host fact once
 * and returns an owned contribution. It adds no second Broker, Sandbox, Policy or Runtime.
 * {@link HostGuardedSandboxProvider} stays the only process owner, {@link DefaultExecutionBroker}
 * owns execution records and {@link ExecutionToolProvider} stays the only model-visible entry, so
 * the declared Tool keeps the ordinary Runtime boundary: one exact Approval per invocation, the
 * frozen invocation digest, capability {@code execution_run}, and bounded, redacted output.
 *
 * <pre>{@code
 * var execution = HostExecutionPlatforms.hostGuarded(
 *         new HostExecutionPlatforms.HostExecutionSpec(
 *                 workspaceRoot,
 *                 dataRoot,
 *                 Duration.ofSeconds(120),
 *                 Duration.ofMinutes(10),
 *                 1_048_576,
 *                 2_000),
 *         caller.tenant(),
 *         caller.principal());
 * HaifaAgentBuilder builder = HaifaAgents.builder(profile)
 *         .persistence(sqlite.persistence())
 *         .conversation(sqlite.conversation());
 * execution.applyTo(builder); // registers execution_run with the declared timeout bound
 * return builder.build();
 * }</pre>
 *
 * <p>The workspace is the one registered host directory: the model can select neither another
 * workspace nor another working directory, and every invocation is checked against the frozen
 * assembly before it reaches the Sandbox. Host scratch space lives below {@code dataRoot}, and the
 * host environment is resolved once with the baseline allowlist, so unrelated host variables never
 * reach the process.
 *
 * <p>The frozen identity of the declared Tool is bound to the declared host workspace: the Tool
 * definition identity, the provider binding reference ({@code host:execution:v1:sha256:...}) and
 * the Workspace revision all carry the canonical path fingerprint and the physical directory
 * fingerprint of the host root. A resumed Run whose persisted binding was frozen against another
 * - or a replaced - directory therefore fails closed instead of dispatching an approved command
 * through a different workspace.
 *
 * <p>The entry cannot enforce network denial: the process is an ordinary host process. The
 * declared Tool therefore always carries the host network side effect, the network is never
 * reported as absent and the standard approval policy gates the invocation.
 * {@link HostExecutionPlatform#applyTo} also installs the execution-aware Approval prompt, which
 * shows what an approval would run as the current OS user; a product that owns its own builder
 * path wires {@link HostExecutionPlatform#approvalPromptFormatter()} itself.
 *
 * <p>The entry does not isolate the host: the process runs as the current OS user with the ordinary
 * host filesystem, network and kernel, exactly like the other host-guarded products. It also does
 * not install the execution-specific Tool schema validator, because the SDK freezes one Tool
 * platform and does not multiplex schema validators. A product that owns its own Tool platform
 * registers {@link HostExecutionPlatform#registration()} on that platform and may wrap its schema
 * validator with the execution validator, which is what the Personal Assistant does.
 */
public final class HostExecutionPlatforms {
    private static final String WORKSPACE_ID = "host-execution";
    private static final String PROJECT_ID = "host-execution";
    private static final String WORKSPACE_REVISION_DIGEST = "host-execution-v1";
    private static final String SANDBOX_PROFILE_ID = "host-guarded-execution";
    private static final String PROFILE_POLICY_VERSION = "host-execution-entry-v1";
    private static final String BINDING_NAMESPACE = "io.haifa.agent.sdk.starter.host-execution/v1";
    private static final String BINDING_REFERENCE_PREFIX = "host:execution:v1:";
    private static final int MAX_APPROVAL_VALUE_LENGTH = 512;
    private static final int MAX_APPROVAL_PROMPT_LENGTH = 4_000;
    private static final String SCRATCH_DIRECTORY_NAME = "host-execution-scratch";
    private static final Set<String> CAPABILITIES = Set.of("execution_run");

    private HostExecutionPlatforms() {}

    /**
     * Resolves the trusted host and returns the owned execution contribution for one builder.
     *
     * <p>Resolution is fail closed. A host directory that is not an existing directory, a timeout or
     * output bound the execution Tool rejects, a host shell that cannot be found, a Sandbox profile
     * the provider cannot preflight, or a host user environment that cannot be resolved all throw
     * {@link HaifaAgentException} with a safe code, and nothing is registered on any builder.
     *
     * @param spec product declaration of host root, data root, timeouts and output budget
     * @param tenant trusted tenant the Runs of this product are started with
     * @param principal trusted principal the Runs of this product are started with
     * @return the resolved host execution contribution; never {@code null}
     */
    public static HostExecutionPlatform hostGuarded(HostExecutionSpec spec, TenantRef tenant, PrincipalRef principal) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(tenant, "tenant must not be null");
        Objects.requireNonNull(principal, "principal must not be null");
        Path workspaceRoot = spec.workspaceRoot();
        String workspaceIdentity = workspaceIdentity(workspaceRoot);
        Path scratchRoot = scratchRoot(spec.dataRoot());
        IdentifierGenerator identifiers = new UuidV7IdentifierGenerator();
        TimeProvider time = Clock.systemUTC()::instant;
        var workspaces = new InMemoryWorkspaceStore();
        var locations = new HostWorkspaceLocationStore();
        WorkspaceId workspaceId = new WorkspaceId(WORKSPACE_ID);
        locations.register(workspaceId, workspaceRoot);
        workspaces.create(Workspace.provision(
                        workspaceId,
                        new ProjectId(PROJECT_ID),
                        WorkspaceRevision.initial(WORKSPACE_REVISION_DIGEST + ":" + workspaceIdentity),
                        time.now())
                .activate(time.now()));
        HostShell shell = hostShell();
        var host = new HostGuardedSandboxProvider(workspaces, locations, identifiers, time, shell, scratchRoot);
        ScriptRuntimeResolver runtimes = HostScriptRuntimeResolver.currentHost(Optional.empty(), Optional.empty());
        ResolvedHostEnvironment hostEnvironment = HostExecutionEnvironmentResolver.resolveHostUser(
                System.getenv(),
                System.getProperty("os.name", ""),
                Path.of(System.getProperty("user.home", ".")),
                spec.dataRoot(),
                workspaceRoot,
                scratchRoot,
                Set.of());
        Map<String, String> environment = hostEnvironment.environment();
        SandboxProfile profile = sandboxProfile(host, runtimes, hostEnvironment.allowedEnvironmentNames());
        preflight(host, profile);
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
                ignored -> ResolvedExecutionEnvironment.of(environment),
                new FrozenHostExecutionPolicy(configuration, tenant, principal, workspaceId),
                new ImmutableSandboxProfileRegistry(List.of(profile)),
                new ImmutableSandboxProviderRegistry(List.of(host)),
                workspaces);
        var provider = new ExecutionToolProvider(
                broker,
                identifiers,
                time,
                ignored -> new ExecutionInvocationScopeResolver.ExecutionInvocationScope(workspaceId, CAPABILITIES),
                configuration);
        String profileIdentity = profile.ref().value() + "@" + profile.ref().version() + "+" + workspaceIdentity;
        // A host process cannot be network-isolated, so the host network side effect is always
        // declared instead of letting a product declare a denial this Sandbox cannot enforce.
        ToolDefinition definition = ExecutionToolDefinitionFactory.create(
                profileIdentity,
                provider.configurationIdentity(),
                provider.scratchSpecDigest(),
                spec.maximumTimeout(),
                true,
                false,
                runtimes.languages());
        return new HostGuardedPlatform(
                new ToolRegistration(
                        ExecutionToolDefinitionFactory.ALIAS,
                        definition,
                        BINDING_REFERENCE_PREFIX + workspaceIdentity,
                        provider),
                previewPublisher,
                spec.defaultTimeout(),
                profileIdentity,
                shell.displayName());
    }

    /**
     * Product declaration of one trusted host execution platform.
     *
     * <p>Every value is a trusted product fact: the model never supplies a Host path, timeout bound
     * or network policy. Timeout and output bounds are validated by
     * {@link ExecutionToolConfiguration}, so the declaration has exactly one authority.
     *
     * <p>There is no network flag: this host-backed entry cannot enforce network denial, so the
     * declared Tool always carries the host network side effect and the approval surface never
     * reports the network as absent.
     *
     * @param workspaceRoot existing host directory the Agent may execute in; it becomes the single
     *     registered Workspace
     * @param dataRoot product-owned data directory; host scratch space is created below it and it is
     *     excluded from the inherited host environment
     * @param defaultTimeout timeout used when the model omits {@code timeoutMillis}; a product
     *     definition, not a Runtime floor
     * @param maximumTimeout product-declared model-visible maximum; bounded by
     *     {@link ExecutionToolConfiguration#MAXIMUM_DECLARED_TIMEOUT}
     * @param maximumOutputBytes bounded output budget of one invocation
     * @param maximumOutputLines bounded output line budget of one invocation
     */
    public record HostExecutionSpec(
            Path workspaceRoot,
            Path dataRoot,
            Duration defaultTimeout,
            Duration maximumTimeout,
            int maximumOutputBytes,
            int maximumOutputLines) {
        public HostExecutionSpec {
            workspaceRoot = hostDirectory(workspaceRoot, "workspaceRoot");
            dataRoot = Objects.requireNonNull(dataRoot, "dataRoot must not be null")
                    .toAbsolutePath()
                    .normalize();
            defaultTimeout = Objects.requireNonNull(defaultTimeout, "defaultTimeout must not be null");
            maximumTimeout = Objects.requireNonNull(maximumTimeout, "maximumTimeout must not be null");
        }
    }

    /**
     * One owned host execution contribution with the frozen Tool registration, builder wiring and
     * the process-local output preview publisher of the same platform.
     *
     * <p>Call {@link #applyTo} at most once. When applied, the platform registers itself as a managed
     * resource, so an Agent that builds successfully closes it on
     * {@link io.haifa.agent.sdk.api.HaifaAgent#close()}. A caller that abandons the build before the
     * Agent adopts the resource must {@link #close()} it explicitly.
     */
    public interface HostExecutionPlatform extends AutoCloseable {
        /**
         * Returns the immutable registered Tool aliases of this contribution. Reading neither
         * executes a process nor authorizes a Tool.
         */
        Set<String> toolNames();

        /**
         * Returns the exact reviewed Tool registration this contribution hands to a builder.
         *
         * <p>A product that owns its own Tool platform registers this value on that platform instead
         * of calling {@link #applyTo}; the registration is immutable and may be read after close.
         */
        ToolRegistration registration();

        /**
         * Returns the process-local publisher of bounded Tool output previews produced by this
         * platform. Subscribing is optional and never changes execution results.
         */
        ToolOutputPreviewPublisher previewPublisher();

        /** Trusted, non-secret description of the resolved host and sandbox profile for diagnostics. */
        String securitySummary();

        /**
         * Returns the execution-aware Approval prompt formatter of this platform.
         *
         * <p>{@link #applyTo} installs the same formatter on the builder, so one
         * {@code execution_run} approval shows what will run as the current OS user: the mode, the
         * language, the command or script, the arguments, the purpose, the declared timeout, the
         * output bound, the host provider and the invocation digest. A product that owns its own
         * builder path wires this formatter itself, exactly like the Personal Assistant wires its
         * execution platform prompt.
         */
        ProductApprovalPromptFormatter approvalPromptFormatter();

        /**
         * Wires the frozen Tool registration, the execution-aware Approval prompt and the managed
         * resource into a builder.
         *
         * <p>The execution-aware formatter claims the builder's single Approval-prompt slot and
         * delegates every other Tool to {@link ProductApprovalPromptFormatter#defaultFormatter()}.
         * A product that needs another presentation for other Tools sets its own
         * {@code toolApprovalPrompts} after {@code applyTo}.
         *
         * @param builder builder that has not applied this platform yet
         * @throws IllegalStateException when called more than once
         */
        void applyTo(HaifaAgentBuilder builder);

        /** Releases everything this contribution owns. Idempotent. */
        @Override
        void close();
    }

    private static final class HostGuardedPlatform implements HostExecutionPlatform {
        private final ToolRegistration registration;
        private final ToolOutputPreviewPublisher previewPublisher;
        private final Duration declaredDefaultTimeout;
        private final String securitySummary;
        private final AtomicBoolean applied = new AtomicBoolean();

        private HostGuardedPlatform(
                ToolRegistration registration,
                ToolOutputPreviewPublisher previewPublisher,
                Duration declaredDefaultTimeout,
                String profileIdentity,
                String shellDisplayName) {
            this.registration = Objects.requireNonNull(registration, "registration must not be null");
            this.previewPublisher = Objects.requireNonNull(previewPublisher, "previewPublisher must not be null");
            this.declaredDefaultTimeout =
                    Objects.requireNonNull(declaredDefaultTimeout, "declaredDefaultTimeout must not be null");
            this.securitySummary = "host-guarded (current OS user, no strong isolation), shell="
                    + shellDisplayName
                    + ", network=host (ordinary local network, not enforced by this sandbox)"
                    + ", profile=" + profileIdentity;
        }

        @Override
        public Set<String> toolNames() {
            return Set.of(registration.alias().value());
        }

        @Override
        public ToolRegistration registration() {
            return registration;
        }

        @Override
        public ToolOutputPreviewPublisher previewPublisher() {
            return previewPublisher;
        }

        @Override
        public String securitySummary() {
            return securitySummary;
        }

        @Override
        public void applyTo(HaifaAgentBuilder builder) {
            Objects.requireNonNull(builder, "builder must not be null");
            if (!applied.compareAndSet(false, true)) {
                throw new IllegalStateException("host execution platform is already applied to a builder");
            }
            builder.toolRegistrations(List.of(registration))
                    .managedResource(this)
                    .toolApprovalPrompts(approvalPromptFormatter());
        }

        @Override
        public ProductApprovalPromptFormatter approvalPromptFormatter() {
            return this::approvalPrompt;
        }

        /** Presents exactly what one approved invocation runs as the current OS user. */
        private ApprovalPrompt approvalPrompt(FrozenToolBinding binding, ToolCall call, boolean reauthentication) {
            if (!registration
                    .definition()
                    .name()
                    .value()
                    .equals(binding.definition().name().value())) {
                return ProductApprovalPromptFormatter.defaultFormatter().format(binding, call, reauthentication);
            }
            var arguments = call.arguments().values();
            String mode = approvalValue(arguments.get("mode"));
            String language = approvalValue(arguments.getOrDefault("language", "default-shell"));
            String content = approvalValue(arguments.get("content"));
            String args = approvalValue(arguments.getOrDefault("args", List.of()));
            String purpose = approvalValue(arguments.get("purpose"));
            String timeout = approvalValue(arguments.getOrDefault("timeoutMillis", declaredDefaultTimeout.toMillis()));
            String digest = SandboxConfigurationDigest.sha256Fields(
                            List.of(mode, language, content, args, purpose, timeout))
                    .value();
            String summary = (reauthentication ? "Reauthenticate and approve host execution" : "Approve host execution")
                    + "\nMode: " + mode
                    + "\nLanguage: " + language
                    + "\n" + ("SCRIPT".equals(mode) ? "Script: " : "Command: ") + content
                    + "\nArgs: " + args
                    + "\nPurpose: " + purpose
                    + "\nTimeout: " + timeout + " ms"
                    + "\nOutput: bounded and redacted"
                    + "\nWorkspace: the single registered host directory; the model cannot select the"
                    + " working directory"
                    + "\nProvider: trusted host process as the current OS user, no strong isolation"
                    + "\nNetwork: ordinary host network; this sandbox cannot deny it"
                    + "\nInvocation digest: " + digest
                    + "\nRisks: HIGH, PROCESS_EXECUTION, NETWORK_ACCESS, NON_IDEMPOTENT, host file access";
            return ApprovalPrompt.of(bounded(summary, MAX_APPROVAL_PROMPT_LENGTH));
        }

        /**
         * Releases nothing today: every process lease is opened and closed per invocation, and the
         * Execution stores are ordinary in-memory objects owned by this Agent. The method exists so
         * a caller that abandons a build releases the contribution exactly like the MCP platform.
         */
        @Override
        public void close() {}
    }

    private static HostShell hostShell() {
        try {
            return HostShell.auto();
        } catch (IllegalStateException exception) {
            throw new HaifaAgentException(
                    "HOST_EXECUTION_UNAVAILABLE",
                    "product.assemble",
                    "execution",
                    "no supported host shell is available",
                    exception);
        }
    }

    /**
     * Stable identity of the declared host workspace: the canonical path fingerprint plus the
     * physical directory fingerprint. It enters the frozen Tool identity, so a resumed Run whose
     * persisted binding was frozen against another - or a replaced - directory fails closed.
     */
    private static String workspaceIdentity(Path workspaceRoot) {
        return workspaceIdentity(BINDING_NAMESPACE, WORKSPACE_ID, workspaceRoot, "HOST_EXECUTION_UNAVAILABLE");
    }

    static String workspaceIdentity(String namespace, String workspaceId, Path workspaceRoot, String errorCode) {
        try {
            return SandboxConfigurationDigest.sha256Fields(List.of(
                            BINDING_NAMESPACE,
                            WORKSPACE_ID,
                            HostWorkspaceLocationStore.fingerprintFor(workspaceRoot),
                            HostWorkspaceLocationStore.physicalFingerprintFor(workspaceRoot)))
                    .value();
        } catch (IllegalStateException exception) {
            throw new HaifaAgentException(
                    errorCode,
                    "product.assemble",
                    "execution",
                    "the declared host workspace identity is unavailable",
                    exception);
        }
    }

    static String approvalValue(Object value) {
        if (value == null) {
            return "(none)";
        }
        if (value instanceof List<?> list) {
            return list.isEmpty()
                    ? "(none)"
                    : bounded(
                            list.stream().map(String::valueOf).collect(Collectors.joining(" ")),
                            MAX_APPROVAL_VALUE_LENGTH);
        }
        String rendered = bounded(String.valueOf(value).strip(), MAX_APPROVAL_VALUE_LENGTH);
        return rendered.isEmpty() ? "(none)" : rendered;
    }

    static String bounded(String value, int maximumLength) {
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength) + "...[truncated]";
    }

    private static SandboxProfile sandboxProfile(
            HostGuardedSandboxProvider host, ScriptRuntimeResolver runtimes, Set<String> environmentNames) {
        return sandboxProfile(host, runtimes, environmentNames, SANDBOX_PROFILE_ID, PROFILE_POLICY_VERSION);
    }

    static SandboxProfile sandboxProfile(
            HostGuardedSandboxProvider host,
            ScriptRuntimeResolver runtimes,
            Set<String> environmentNames,
            String sandboxProfileId,
            String profilePolicyVersion) {
        String version = "1-"
                + SandboxConfigurationDigest.sha256Fields(List.of(
                                profilePolicyVersion,
                                HostExecutionEnvironmentResolver.POLICY_VERSION,
                                host.configurationDigest().value()))
                        .value()
                        .substring("sha256:".length());
        return SandboxProfile.hostGuarded(
                new SandboxProfileRef(sandboxProfileId, version),
                host.configurationDigest(),
                runtimes.executableNames(),
                environmentNames,
                true);
    }

    private static void preflight(HostGuardedSandboxProvider host, SandboxProfile profile) {
        preflight(host, profile, "HOST_EXECUTION_UNAVAILABLE");
    }

    static void preflight(HostGuardedSandboxProvider host, SandboxProfile profile, String errorCode) {
        try {
            host.preflight(profile);
        } catch (SandboxException exception) {
            throw new HaifaAgentException(errorCode, "product.assemble", "execution", exception.code(), exception);
        }
    }

    private static Path scratchRoot(Path dataRoot) {
        return scratchRoot(dataRoot, SCRATCH_DIRECTORY_NAME, "HOST_EXECUTION_UNAVAILABLE");
    }

    static Path scratchRoot(Path dataRoot, String scratchDirectoryName, String errorCode) {
        Path scratchRoot =
                dataRoot.resolve(scratchDirectoryName).toAbsolutePath().normalize();
        try {
            Files.createDirectories(scratchRoot);
        } catch (IOException exception) {
            throw new HaifaAgentException(
                    errorCode, "product.assemble", "execution", "host scratch space is unavailable", exception);
        }
        return scratchRoot;
    }

    static Path hostDirectory(Path value, String field) {
        Path directory = Objects.requireNonNull(value, field + " must not be null")
                .toAbsolutePath()
                .normalize();
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException(field + " must be an existing directory");
        }
        return directory;
    }
}

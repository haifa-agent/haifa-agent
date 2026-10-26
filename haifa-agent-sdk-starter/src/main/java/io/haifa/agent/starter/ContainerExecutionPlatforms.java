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
import io.haifa.agent.execution.core.tool.ExecutionOperatingSystem;
import io.haifa.agent.execution.core.tool.ExecutionToolConfiguration;
import io.haifa.agent.execution.core.tool.ExecutionToolDefinitionFactory;
import io.haifa.agent.execution.core.tool.ExecutionToolProvider;
import io.haifa.agent.execution.core.tool.ScriptRuntimeAdapter;
import io.haifa.agent.execution.core.tool.ScriptRuntimeResolver;
import io.haifa.agent.project.core.store.InMemoryWorkspaceStore;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.hostworkspace.HostWorkspaceLocationStore;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import io.haifa.agent.runtime.api.ApprovalPrompt;
import io.haifa.agent.sandbox.api.SandboxConfigurationDigest;
import io.haifa.agent.sandbox.api.SandboxProfile;
import io.haifa.agent.sandbox.docker.DockerAioSandboxConfiguration;
import io.haifa.agent.sandbox.docker.DockerAioSandboxProvider;
import io.haifa.agent.sdk.api.HaifaAgentBuilder;
import io.haifa.agent.sdk.api.HaifaAgentException;
import io.haifa.agent.sdk.contribution.ProductApprovalPromptFormatter;
import io.haifa.agent.sdk.contribution.ToolRegistration;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.ToolDefinition;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

/**
 * Public one-shot Shell assembly for the explicit Linux {@code docker-aio} sandbox.
 *
 * <p>This is the container counterpart of {@link HostExecutionPlatforms}. It adds no second
 * Broker, Runtime, Policy or execution entry: the same {@link DefaultExecutionBroker},
 * {@link ExecutionToolProvider} and {@code execution_run} Tool are wired to
 * {@link DockerAioSandboxProvider}. The model can select neither image, engine socket, mounts,
 * network, resources nor a host working directory.
 *
 * <p>The declared Tool keeps the conservative network side effect in this first candidate. Linux
 * isolation is still NOT_RUN, so the assembly does not claim that workload egress is denied.
 */
public final class ContainerExecutionPlatforms {
    private static final String WORKSPACE_ID = "docker-aio-execution";
    private static final String PROJECT_ID = "docker-aio-execution";
    private static final String WORKSPACE_REVISION_DIGEST = "docker-aio-execution-v1";
    private static final String SANDBOX_PROFILE_ID = "docker-aio-execution";
    private static final String PROFILE_POLICY_VERSION = "docker-aio-execution-entry-v1";
    private static final String BINDING_NAMESPACE = "io.haifa.agent.sdk.starter.docker-aio-execution/v1";
    private static final String BINDING_REFERENCE_PREFIX = "docker-aio:execution:v1:";
    private static final int MAX_APPROVAL_VALUE_LENGTH = 512;
    private static final int MAX_APPROVAL_PROMPT_LENGTH = 4_000;
    private static final String UNAVAILABLE_CODE = "CONTAINER_EXECUTION_UNAVAILABLE";
    private static final Set<String> CAPABILITIES = Set.of("execution_run");

    private ContainerExecutionPlatforms() {}

    /**
     * Resolves one trusted container execution contribution for a builder.
     *
     * <p>Resolution performs no Docker I/O. Docker/engine/image/network failures fail closed at
     * execution time and never fall back to the host provider.
     */
    public static HostExecutionPlatforms.HostExecutionPlatform dockerAio(
            ContainerExecutionSpec spec,
            DockerAioSandboxConfiguration configuration,
            TenantRef tenant,
            PrincipalRef principal) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(configuration, "configuration must not be null");
        Objects.requireNonNull(tenant, "tenant must not be null");
        Objects.requireNonNull(principal, "principal must not be null");
        Path workspaceRoot = HostExecutionPlatforms.hostDirectory(spec.workspaceRoot(), "workspaceRoot");
        String workspaceIdentity = HostExecutionPlatforms.workspaceIdentity(
                BINDING_NAMESPACE, WORKSPACE_ID, workspaceRoot, UNAVAILABLE_CODE);
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
        var provider = new DockerAioSandboxProvider(configuration, workspaces, locations, identifiers, time);
        ScriptRuntimeResolver runtimes = containerRuntimes(configuration);
        SandboxProfile profile = sandboxProfile(provider, runtimes);
        try {
            provider.preflight(profile);
        } catch (RuntimeException exception) {
            throw new HaifaAgentException(
                    UNAVAILABLE_CODE, "product.assemble", "execution", "sandbox profile preflight failed", exception);
        }
        ToolOutputPreviewPublisher previewPublisher = new TransientToolOutputPreviewPublisher();
        ExecutionToolConfiguration configurationTool = new ExecutionToolConfiguration(
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
                ignored -> ResolvedExecutionEnvironment.of(Map.of()),
                new FrozenHostExecutionPolicy(
                        configurationTool,
                        tenant,
                        principal,
                        workspaceId,
                        "CONTAINER_EXECUTION_MANAGED_SESSION_DENIED",
                        "CONTAINER_EXECUTION_ORIGIN_DENIED",
                        "CONTAINER_EXECUTION_ASSEMBLY_DENIED"),
                new ImmutableSandboxProfileRegistry(List.of(profile)),
                new ImmutableSandboxProviderRegistry(List.of(provider)),
                workspaces);
        var execution = new ExecutionToolProvider(
                broker,
                identifiers,
                time,
                ignored -> new ExecutionInvocationScopeResolver.ExecutionInvocationScope(workspaceId, CAPABILITIES),
                configurationTool);
        String profileIdentity = profile.ref().value() + "@" + profile.ref().version() + "+" + workspaceIdentity;
        ToolDefinition definition = ExecutionToolDefinitionFactory.create(
                profileIdentity,
                execution.configurationIdentity(),
                execution.scratchSpecDigest(),
                spec.maximumTimeout(),
                true,
                false,
                runtimes.languages());
        return new ContainerPlatform(
                new ToolRegistration(
                        ExecutionToolDefinitionFactory.ALIAS,
                        definition,
                        BINDING_REFERENCE_PREFIX + workspaceIdentity,
                        execution),
                previewPublisher,
                spec.defaultTimeout(),
                profileIdentity,
                configuration.image(),
                configuration.containerWorkspaceRoot(),
                provider);
    }

    /** Product declaration of one trusted container Shell platform. */
    public record ContainerExecutionSpec(
            Path workspaceRoot,
            Duration defaultTimeout,
            Duration maximumTimeout,
            int maximumOutputBytes,
            int maximumOutputLines) {
        public ContainerExecutionSpec {
            workspaceRoot = HostExecutionPlatforms.hostDirectory(workspaceRoot, "workspaceRoot");
            defaultTimeout = Objects.requireNonNull(defaultTimeout, "defaultTimeout must not be null");
            maximumTimeout = Objects.requireNonNull(maximumTimeout, "maximumTimeout must not be null");
        }
    }

    private static ScriptRuntimeResolver containerRuntimes(DockerAioSandboxConfiguration configuration) {
        List<ScriptRuntimeAdapter> adapters = new ArrayList<>();
        configuration
                .runtimes()
                .forEach((language, executable) -> adapters.add(
                        switch (language) {
                            case "bash" -> ScriptRuntimeResolver.bash(executable);
                            case "python" -> ScriptRuntimeResolver.python(executable);
                            default ->
                                throw new HaifaAgentException(
                                        UNAVAILABLE_CODE,
                                        "product.assemble",
                                        "execution",
                                        "container runtime language is not supported");
                        }));
        return new ScriptRuntimeResolver(ExecutionOperatingSystem.LINUX, adapters);
    }

    private static SandboxProfile sandboxProfile(DockerAioSandboxProvider provider, ScriptRuntimeResolver runtimes) {
        String version = "1-"
                + SandboxConfigurationDigest.sha256Fields(List.of(
                                PROFILE_POLICY_VERSION,
                                provider.configurationDigest().value()))
                        .value()
                        .substring("sha256:".length());
        return new SandboxProfile(
                new SandboxProfileRef(SANDBOX_PROFILE_ID, version),
                DockerAioSandboxProvider.PROVIDER_ID,
                provider.configurationDigest(),
                runtimes.executableNames(),
                Set.of(),
                true);
    }

    private static final class ContainerPlatform implements HostExecutionPlatforms.HostExecutionPlatform {
        private final ToolRegistration registration;
        private final ToolOutputPreviewPublisher previewPublisher;
        private final Duration declaredDefaultTimeout;
        private final String securitySummary;
        private final DockerAioSandboxProvider provider;
        private final AtomicBoolean applied = new AtomicBoolean();

        private ContainerPlatform(
                ToolRegistration registration,
                ToolOutputPreviewPublisher previewPublisher,
                Duration declaredDefaultTimeout,
                String profileIdentity,
                String image,
                String containerWorkspaceRoot,
                DockerAioSandboxProvider provider) {
            this.registration = Objects.requireNonNull(registration, "registration must not be null");
            this.previewPublisher = Objects.requireNonNull(previewPublisher, "previewPublisher must not be null");
            this.declaredDefaultTimeout =
                    Objects.requireNonNull(declaredDefaultTimeout, "declaredDefaultTimeout must not be null");
            this.provider = provider;
            this.securitySummary = "docker-aio (one-shot Linux Docker container), image="
                    + image
                    + ", network=isolated candidate (Linux verification NOT_RUN)"
                    + ", workspace=" + containerWorkspaceRoot
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
                throw new IllegalStateException("container execution platform is already applied to a builder");
            }
            builder.toolRegistrations(List.of(registration))
                    .managedResource(this)
                    .toolApprovalPrompts(approvalPromptFormatter());
        }

        @Override
        public ProductApprovalPromptFormatter approvalPromptFormatter() {
            return this::approvalPrompt;
        }

        private ApprovalPrompt approvalPrompt(FrozenToolBinding binding, ToolCall call, boolean reauthentication) {
            if (!registration
                    .definition()
                    .name()
                    .value()
                    .equals(binding.definition().name().value())) {
                return ProductApprovalPromptFormatter.defaultFormatter().format(binding, call, reauthentication);
            }
            var arguments = call.arguments().values();
            String mode = HostExecutionPlatforms.approvalValue(arguments.get("mode"));
            String language = HostExecutionPlatforms.approvalValue(arguments.getOrDefault("language", "default-shell"));
            String content = HostExecutionPlatforms.approvalValue(arguments.get("content"));
            String args = HostExecutionPlatforms.approvalValue(arguments.getOrDefault("args", List.of()));
            String purpose = HostExecutionPlatforms.approvalValue(arguments.get("purpose"));
            String timeout = HostExecutionPlatforms.approvalValue(
                    arguments.getOrDefault("timeoutMillis", declaredDefaultTimeout.toMillis()));
            String digest = SandboxConfigurationDigest.sha256Fields(
                            List.of(mode, language, content, args, purpose, timeout))
                    .value();
            String summary = (reauthentication
                            ? "Reauthenticate and approve container execution"
                            : "Approve container execution")
                    + "\nMode: " + mode
                    + "\nLanguage: " + language
                    + "\n" + ("SCRIPT".equals(mode) ? "Script: " : "Command: ") + content
                    + "\nArgs: " + args
                    + "\nPurpose: " + purpose
                    + "\nTimeout: " + timeout + " ms"
                    + "\nOutput: bounded and redacted"
                    + "\nWorkspace: the single registered workspace; the model cannot select the"
                    + " working directory"
                    + "\nProvider: docker-aio one-shot container; no host fallback"
                    + "\nNetwork: workload network isolation is configured, Linux verification NOT_RUN"
                    + "\nInvocation digest: " + digest
                    + "\nRisks: HIGH, PROCESS_EXECUTION, container execution";
            return ApprovalPrompt.of(HostExecutionPlatforms.bounded(summary, MAX_APPROVAL_PROMPT_LENGTH));
        }

        @Override
        public void close() {
            if (provider != null) {
                try {
                    provider.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}

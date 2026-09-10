package io.haifa.agent.cli;

import io.haifa.agent.application.project.policy.CodingAgentExecutionPolicy;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationProfileProvider;
import io.haifa.agent.application.project.tool.CodingToolchainEnvironmentProfile;
import io.haifa.agent.application.project.tool.ProjectExecutionRecoveryAuthorization;
import io.haifa.agent.application.project.tool.ProjectExecutionToolOperations;
import io.haifa.agent.application.project.workspace.WorkspaceAccessMode;
import io.haifa.agent.application.project.workspace.WorkspaceAccessStore;
import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.execution.api.ExecutionEnvironmentRef;
import io.haifa.agent.execution.api.ExecutionOutputObserver;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.execution.core.DefaultExecutionBroker;
import io.haifa.agent.execution.core.ImmutableSandboxProfileRegistry;
import io.haifa.agent.execution.core.ImmutableSandboxProviderRegistry;
import io.haifa.agent.execution.core.store.InMemoryExecutionOutputStore;
import io.haifa.agent.execution.core.store.InMemoryExecutionStore;
import io.haifa.agent.execution.host.change.LocalIncrementalWorkspaceChangeObserver;
import io.haifa.agent.policy.api.PolicyDigest;
import io.haifa.agent.project.hostworkspace.HostWorkspaceFileService;
import io.haifa.agent.project.hostworkspace.HostWorkspaceLocationStore;
import io.haifa.agent.project.hostworkspace.scope.AuthorizedWorkspaceProvisioning;
import io.haifa.agent.project.store.WorkspaceBindingStore;
import io.haifa.agent.project.store.WorkspaceStore;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.runtime.core.tool.RuntimeToolExecutionVerifier;
import io.haifa.agent.sandbox.api.SandboxException;
import io.haifa.agent.sandbox.api.SandboxPreflight;
import io.haifa.agent.sandbox.api.SandboxProfile;
import io.haifa.agent.sandbox.api.SandboxProvider;
import io.haifa.agent.sandbox.host.HostExecutionEnvironmentResolver;
import io.haifa.agent.sandbox.host.HostGuardedSandboxProvider;
import io.haifa.agent.sandbox.host.HostShell;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Owns the CLI's trusted local execution assembly without exposing provider controls to the model. */
final class CliExecutionPlatform implements AutoCloseable {
    private final ProjectExecutionToolOperations operations;
    private final ProjectExecutionToolOperations permissionOperations;
    private final SandboxProfile profile;
    private final SandboxProfile permissionProfile;
    private final String shellDisplayName;
    private final String securitySummary;
    private final LocalIncrementalWorkspaceChangeObserver workspaceChanges;
    private final CliRepositoryBaselineSupport repositoryBaselines;

    private CliExecutionPlatform(
            ProjectExecutionToolOperations operations,
            ProjectExecutionToolOperations permissionOperations,
            SandboxProfile profile,
            SandboxProfile permissionProfile,
            String shellDisplayName,
            String securitySummary,
            LocalIncrementalWorkspaceChangeObserver workspaceChanges,
            CliRepositoryBaselineSupport repositoryBaselines) {
        this.operations = operations;
        this.permissionOperations = permissionOperations;
        this.profile = profile;
        this.permissionProfile = permissionProfile;
        this.shellDisplayName = shellDisplayName;
        this.securitySummary = securitySummary;
        this.workspaceChanges = workspaceChanges;
        this.repositoryBaselines = repositoryBaselines;
    }

    static CliExecutionPlatform create(
            CliConfiguration.Execution configuration,
            WorkspaceStore workspaces,
            WorkspaceBindingStore bindings,
            HostWorkspaceLocationStore locations,
            HostWorkspaceFileService files,
            IdentifierGenerator identifiers,
            TimeProvider time,
            WorkspaceId workspaceId,
            Path workspaceRoot,
            PrintStream output,
            Map<String, String> hostEnvironment,
            CodingVerificationProfileProvider verificationProfiles,
            AuthorizedWorkspaceProvisioning provisioning,
            WorkspaceAccessStore workspaceAccess,
            TenantRef tenant,
            PrincipalRef principal,
            RuntimeToolExecutionVerifier runtimeExecutionVerifier,
            ProjectExecutionRecoveryAuthorization recoveryAuthorization) {
        Objects.requireNonNull(configuration, "configuration must not be null");
        Objects.requireNonNull(verificationProfiles, "verificationProfiles must not be null");
        Objects.requireNonNull(provisioning, "provisioning must not be null");
        Objects.requireNonNull(workspaceAccess, "workspaceAccess must not be null");
        Objects.requireNonNull(tenant, "tenant must not be null");
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(runtimeExecutionVerifier, "runtimeExecutionVerifier must not be null");
        Objects.requireNonNull(recoveryAuthorization, "recoveryAuthorization must not be null");
        HostShell shell = shell(configuration);
        Path controlRoot = controlRoot();
        Path scratchRoot = controlRoot.resolve("host-scratch");
        var host = new HostGuardedSandboxProvider(
                workspaces, bindings, locations, identifiers, time, shell, scratchRoot);
        if (!configuration.provider().equals(host.providerId())) {
            throw new IllegalArgumentException(
                    "SANDBOX_ADAPTER_UNAVAILABLE: configured execution provider is unavailable");
        }
        var resolvedEnvironment = CliExecutionEnvironment.resolve(
                configuration,
                hostEnvironment,
                System.getProperty("os.name", ""),
                Path.of(System.getProperty("user.home", ".")),
                controlRoot,
                workspaceRoot,
                scratchRoot);
        Map<String, String> environment = resolvedEnvironment.environment();
        var ignorePolicy = CliWorkspaceChangeIgnorePolicy.load(workspaceRoot);
        SandboxProfile profile =
                profile(configuration, host, resolvedEnvironment.allowedEnvironmentNames(), ignorePolicy.version());
        var profileRegistry = new ImmutableSandboxProfileRegistry(List.of(profile));
        var providerRegistry = new ImmutableSandboxProviderRegistry(List.of(host));
        SandboxPreflight preflight;
        try {
            preflight = providerRegistry.resolve(profile).preflight(profile);
        } catch (SandboxException exception) {
            throw new IllegalArgumentException(exception.code() + ": " + exception.getMessage());
        }
        ExecutionEnvironmentRef environmentRef = new ExecutionEnvironmentRef(
                List.of("cli-execution-" + profile.contentDigest().value()));
        var workspaceChanges = new LocalIncrementalWorkspaceChangeObserver(workspaceId, workspaceRoot, ignorePolicy);
        var broker = new DefaultExecutionBroker(
                new InMemoryExecutionStore(),
                new InMemoryExecutionOutputStore(),
                requestedEnvironment -> io.haifa.agent.execution.api.ResolvedExecutionEnvironment.of(environment),
                new CodingAgentExecutionPolicy(
                        runtimeExecutionVerifier,
                        recoveryAuthorization,
                        workspaceAccess,
                        provisioning,
                        tenant,
                        principal,
                        environmentRef,
                        environmentRef,
                        profile.ref(),
                        profile.ref(),
                        CodingToolchainEnvironmentProfile.defaultScratchSpace(),
                        configuration.defaultTimeout(),
                        configuration.maximumTimeout(),
                        configuration.maxOutputBytes(),
                        configuration.maxProcesses()),
                profileRegistry,
                providerRegistry,
                workspaces,
                bindings,
                workspaceChanges);
        CliRepositoryBaselineSupport repositoryBaselines =
                CliRepositoryBaselineSupport.create(broker, identifiers, profile.ref(), provisioning);
        ExecutionOutputObserver observer = new CliOutputObserver(output);
        var operations = new ProjectExecutionToolOperations(
                broker,
                identifiers,
                time,
                environmentRef,
                profile.ref(),
                configuration.defaultTimeout(),
                configuration.maximumTimeout(),
                configuration.maxOutputBytes(),
                configuration.maxOutputLines(),
                configuration.maxProcesses(),
                observer,
                java.util.function.UnaryOperator.identity(),
                CodingToolchainEnvironmentProfile.defaultScratchSpace(),
                workspaceTargetResolver(provisioning, workspaceAccess, tenant, principal),
                verificationProfiles,
                repositoryBaselines.observer());
        String securitySummary = securitySummary(profile, preflight);
        output.println("Execution security: " + securitySummary);
        return new CliExecutionPlatform(
                operations,
                operations,
                profile,
                profile,
                shell.displayName(),
                securitySummary,
                workspaceChanges,
                repositoryBaselines);
    }

    ProjectExecutionToolOperations operations() {
        return operations;
    }

    io.haifa.agent.application.project.product.coding.delivery.RunRepositoryBaselineRegistry repositoryBaselines() {
        return repositoryBaselines == null ? null : repositoryBaselines.registry();
    }

    static io.haifa.agent.application.project.tool.ExecutionWorkspaceTargetResolver workspaceTargetResolver(
            AuthorizedWorkspaceProvisioning provisioning,
            WorkspaceAccessStore workspaceAccess,
            TenantRef tenant,
            PrincipalRef principal) {
        Objects.requireNonNull(provisioning, "provisioning must not be null");
        Objects.requireNonNull(workspaceAccess, "workspaceAccess must not be null");
        Objects.requireNonNull(tenant, "tenant must not be null");
        Objects.requireNonNull(principal, "principal must not be null");
        return (access, workspaceRef, relativeWorkdir) -> {
            WorkspaceId target = new WorkspaceId(workspaceRef);
            workspaceAccess.require(tenant, principal, target, WorkspaceAccessMode.DEVELOP);
            return provisioning.scope().resolveExecutionDirectory(target, relativeWorkdir);
        };
    }

    ProjectExecutionToolOperations permissionOperations() {
        return permissionOperations;
    }

    SandboxProfile profile() {
        return profile;
    }

    SandboxProfile permissionProfile() {
        return permissionProfile;
    }

    String shellDisplayName() {
        return shellDisplayName;
    }

    String securitySummary() {
        return securitySummary;
    }

    String profileDigest() {
        return profile.ref().value() + "@" + profile.ref().version();
    }

    @Override
    public void close() {
        workspaceChanges.close();
    }

    static String policyResourceDigest(String command, String workdir, String profileDigest) {
        String invocationDigest = PolicyDigest.sha256Fields(List.of(command, workdir));
        invocationDigest = io.haifa.agent.execution.api.ExecutionRequest.digestWithScratch(
                invocationDigest, CodingToolchainEnvironmentProfile.defaultScratchSpace());
        return PolicyDigest.sha256Fields(List.of(invocationDigest, profileDigest));
    }

    private static HostShell shell(CliConfiguration.Execution configuration) {
        Path configured = configuration.shellPath();
        if (configured != null) {
            return configuration.shell().equals("powershell")
                    ? HostShell.powerShell(configured)
                    : HostShell.bash(configured);
        }
        if (configuration.shell().equals("auto")) {
            try {
                return HostShell.auto();
            } catch (IllegalStateException exception) {
                throw new IllegalArgumentException("execution.shell auto found no supported host shell");
            }
        }
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
        if (configuration.shell().equals("bash")) {
            if (windows) throw new IllegalArgumentException("execution.shell bash requires an absolute shellPath");
            return HostShell.bash(Path.of("/bin/bash"));
        }
        if (!windows) throw new IllegalArgumentException("execution.shell powershell requires an absolute shellPath");
        return new HostShell(
                "PowerShell",
                java.util.List.of("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command"));
    }

    /** Private CLI-owned root for host scratch space and environment boundary checks. */
    static Path controlRoot() {
        return Path.of(System.getProperty("java.io.tmpdir"), "haifa-agent-host")
                .toAbsolutePath()
                .normalize();
    }

    static SandboxProfile profile(CliConfiguration.Execution configuration, SandboxProvider provider) {
        Path boundary = Path.of(System.getProperty("java.io.tmpdir"), "haifa-cli-profile-boundary")
                .toAbsolutePath()
                .normalize();
        var environment = CliExecutionEnvironment.resolve(
                configuration, boundary, boundary.resolve("workspace"), boundary.resolve("scratch"));
        return profile(
                configuration, provider, environment.allowedEnvironmentNames(), "cli-workspace-change-unbound-v1");
    }

    private static SandboxProfile profile(
            CliConfiguration.Execution configuration,
            SandboxProvider provider,
            Set<String> inheritedEnvironment,
            String workspaceChangePolicyVersion) {
        List<String> identityFields = new java.util.ArrayList<>();
        identityFields.add("cli-execution-v3");
        identityFields.add(HostExecutionEnvironmentResolver.POLICY_VERSION);
        identityFields.add(workspaceChangePolicyVersion);
        identityFields.add(provider.providerId());
        identityFields.add(provider.configurationDigest().value());
        configuration.inheritEnvironment().stream()
                .sorted()
                .forEach(value -> identityFields.add("environment:" + value));
        CodingToolchainEnvironmentProfile.defaultScratchSpace().environmentNames().stream()
                .sorted()
                .forEach(value -> identityFields.add("scratch-environment:" + value));
        String version = "3-"
                + io.haifa.agent.sandbox.api.SandboxConfigurationDigest.sha256Fields(identityFields)
                        .value()
                        .substring("sha256:".length());
        SandboxProfileRef reference = new SandboxProfileRef("cli-" + provider.providerId(), version);
        Set<String> allowedEnvironment = java.util.stream.Stream.concat(
                        inheritedEnvironment.stream(),
                        CodingToolchainEnvironmentProfile.defaultScratchSpace().environmentNames().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return SandboxProfile.hostGuarded(
                reference, provider.configurationDigest(), Set.of("git"), allowedEnvironment, true);
    }

    static String securitySummary(SandboxProfile profile, SandboxPreflight preflight) {
        String digest = profile.contentDigest().value().substring(0, 12);
        return "provider=" + profile.providerId()
                + " (controlled host execution, trusted local development), adapter="
                + preflight.adapterId()
                + ", network=host (ordinary local network: host loopback/LAN/internet may be reachable), "
                + "current OS user, workspace/outside files/network/CPU/memory/kernel are not isolated, "
                + "approval is not isolation, profile="
                + digest;
    }

    static final class CliOutputObserver implements ExecutionOutputObserver {
        private static final int MAX_PENDING_CHARACTERS = 8192;
        private final PrintStream output;
        private final StringBuilder pending = new StringBuilder();

        CliOutputObserver(PrintStream output) {
            this.output = Objects.requireNonNull(output, "output must not be null");
        }

        @Override
        public synchronized void onOutput(io.haifa.agent.execution.api.ProcessOutputChunk chunk) {
            String text =
                    new String(chunk.bytes(), StandardCharsets.UTF_8).replaceAll("\\u001B\\[[;?0-9]*[ -/]*[@-~]", "");
            StringBuilder safe = new StringBuilder(text.length());
            text.codePoints().forEach(codePoint -> {
                if (codePoint == '\n' || codePoint == '\r' || codePoint == '\t' || !Character.isISOControl(codePoint)) {
                    safe.appendCodePoint(codePoint);
                }
            });
            pending.append(safe);
            if (chunk.endOfStream()) {
                flush(pending.length());
            } else {
                int newline = Math.max(pending.lastIndexOf("\n"), pending.lastIndexOf("\r"));
                if (newline >= 0) flush(newline + 1);
                while (pending.length() > MAX_PENDING_CHARACTERS) flush(MAX_PENDING_CHARACTERS);
            }
        }

        private void flush(int length) {
            if (length > 0) {
                String value = pending.substring(0, length);
                pending.delete(0, length);
                output.print(value);
            }
            output.flush();
        }
    }
}

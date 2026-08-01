package io.haifa.agent.cli;

import io.haifa.agent.application.project.policy.CodingAgentPolicyAssembly;
import io.haifa.agent.application.project.tool.CodingToolchainEnvironmentProfile;
import io.haifa.agent.application.project.tool.ProjectExecutionToolOperations;
import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.execution.api.ExecutionEnvironmentRef;
import io.haifa.agent.execution.api.ExecutionOutputObserver;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.execution.core.DefaultExecutionBroker;
import io.haifa.agent.execution.core.ImmutableSandboxProfileRegistry;
import io.haifa.agent.execution.core.ImmutableSandboxProviderRegistry;
import io.haifa.agent.execution.core.PolicyDecisionExecutionPolicy;
import io.haifa.agent.execution.core.manifest.ManifestBudget;
import io.haifa.agent.execution.core.manifest.ManifestDiffService;
import io.haifa.agent.execution.core.manifest.WorkspaceManifestService;
import io.haifa.agent.execution.core.store.InMemoryExecutionOutputStore;
import io.haifa.agent.execution.core.store.InMemoryExecutionStore;
import io.haifa.agent.policy.api.PolicyDigest;
import io.haifa.agent.project.changeset.FileChangeSetService;
import io.haifa.agent.project.changeset.InMemoryFileChangeSetStore;
import io.haifa.agent.project.changeset.ObservedFileChangeService;
import io.haifa.agent.project.provider.local.LocalWorkspaceFileService;
import io.haifa.agent.project.provider.local.LocalWorkspaceLocationStore;
import io.haifa.agent.project.store.WorkspaceBindingStore;
import io.haifa.agent.project.store.WorkspaceStore;
import io.haifa.agent.sandbox.api.NetworkPolicy;
import io.haifa.agent.sandbox.api.SandboxCapabilities;
import io.haifa.agent.sandbox.api.SandboxException;
import io.haifa.agent.sandbox.api.SandboxFilesystemPolicy;
import io.haifa.agent.sandbox.api.SandboxPreflight;
import io.haifa.agent.sandbox.api.SandboxProfile;
import io.haifa.agent.sandbox.api.SandboxProvider;
import io.haifa.agent.sandbox.api.SandboxWorkspaceAccess;
import io.haifa.agent.sandbox.host.HostGuardedSandboxProvider;
import io.haifa.agent.sandbox.host.HostShell;
import io.haifa.agent.sandbox.localnative.LocalNativePathGrant;
import io.haifa.agent.sandbox.localnative.LocalNativeSandboxConfiguration;
import io.haifa.agent.sandbox.localnative.LocalNativeSandboxProvider;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Owns the CLI's trusted local execution assembly without exposing provider controls to the model. */
final class CliExecutionPlatform {
    private final ProjectExecutionToolOperations operations;
    private final SandboxProfile profile;
    private final String shellDisplayName;
    private final String securitySummary;

    private CliExecutionPlatform(
            ProjectExecutionToolOperations operations,
            SandboxProfile profile,
            String shellDisplayName,
            String securitySummary) {
        this.operations = operations;
        this.profile = profile;
        this.shellDisplayName = shellDisplayName;
        this.securitySummary = securitySummary;
    }

    static CliExecutionPlatform create(
            CliConfiguration.Execution configuration,
            WorkspaceStore workspaces,
            WorkspaceBindingStore bindings,
            LocalWorkspaceLocationStore locations,
            LocalWorkspaceFileService files,
            InMemoryFileChangeSetStore changeSets,
            FileChangeSetService changeSetService,
            IdentifierGenerator identifiers,
            TimeProvider time,
            Clock clock,
            CodingAgentPolicyAssembly policy,
            PrintStream output) {
        Objects.requireNonNull(configuration, "configuration must not be null");
        HostShell shell = shell(configuration);
        LocalNativeSandboxConfiguration localConfiguration = localConfiguration(configuration, shell);
        var host = new HostGuardedSandboxProvider(
                workspaces,
                bindings,
                locations,
                identifiers,
                time,
                shell,
                localConfiguration.controlRoot().resolve("host-scratch"));
        var local =
                new LocalNativeSandboxProvider(workspaces, bindings, locations, identifiers, time, localConfiguration);
        Map<String, SandboxProvider> configuredProviders = Map.of(host.providerId(), host, local.providerId(), local);
        SandboxProvider selected = configuredProviders.get(configuration.provider());
        if (selected == null) {
            throw new IllegalArgumentException(
                    "SANDBOX_ADAPTER_UNAVAILABLE: configured execution provider is unavailable");
        }
        SandboxProfile profile = profile(configuration, selected);
        var profileRegistry = new ImmutableSandboxProfileRegistry(List.of(profile));
        var providerRegistry = new ImmutableSandboxProviderRegistry(configuredProviders.values());
        SandboxPreflight preflight;
        try {
            preflight = providerRegistry.resolve(profile).preflight(profile);
        } catch (SandboxException exception) {
            throw diagnostic(configuration, exception);
        }
        Map<String, String> environment = environment(configuration, profile);
        ExecutionEnvironmentRef environmentRef = new ExecutionEnvironmentRef(
                List.of("cli-execution-" + profile.contentDigest().value()));
        var manifests = new WorkspaceManifestService(
                workspaces,
                files,
                new ManifestBudget(100_000, 1024L * 1024 * 1024, 256L * 1024 * 1024),
                "cli-shell-v1");
        var observedChanges = new ObservedFileChangeService(workspaces, changeSets, changeSetService, time);
        var broker = new DefaultExecutionBroker(
                new InMemoryExecutionStore(),
                new InMemoryExecutionOutputStore(),
                ignored -> environment,
                new PolicyDecisionExecutionPolicy(
                        policy.decisionsStore(), policy.snapshots(), policy.evidence(), clock),
                profileRegistry,
                providerRegistry,
                workspaces,
                bindings,
                manifests,
                new ManifestDiffService(),
                observedChanges);
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
                CodingToolchainEnvironmentProfile.defaultScratchSpace());
        String securitySummary = securitySummary(profile, preflight);
        output.println("Execution security: " + securitySummary);
        return new CliExecutionPlatform(operations, profile, shell.displayName(), securitySummary);
    }

    ProjectExecutionToolOperations operations() {
        return operations;
    }

    SandboxProfile profile() {
        return profile;
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

    static LocalNativeSandboxConfiguration localConfiguration(
            CliConfiguration.Execution configuration, HostShell shell) {
        LocalNativeSandboxConfiguration defaults = LocalNativeSandboxConfiguration.defaults();
        Map<String, LocalNativePathGrant> extraPaths = configuration.extraPathPolicies().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        CliConfiguration.ExtraPathPolicy::id,
                        value -> new LocalNativePathGrant(value.path(), value.readOnly())));
        return new LocalNativeSandboxConfiguration(
                shell.invocationPrefix(),
                defaults.controlRoot(),
                defaults.seatbeltExecutable(),
                defaults.bubblewrapExecutable(),
                extraPaths,
                defaults.sensitivePaths());
    }

    static SandboxProfile profile(CliConfiguration.Execution configuration, SandboxProvider provider) {
        NetworkPolicy network = NetworkPolicy.valueOf(configuration.network().toUpperCase(java.util.Locale.ROOT));
        List<String> identityFields = new java.util.ArrayList<>();
        identityFields.add("cli-execution-v1");
        identityFields.add(provider.providerId());
        identityFields.add(provider.configurationDigest().value());
        identityFields.add(network.name());
        configuration.inheritEnvironment().stream()
                .sorted()
                .forEach(value -> identityFields.add("environment:" + value));
        CodingToolchainEnvironmentProfile.defaultScratchSpace().environmentNames().stream()
                .sorted()
                .forEach(value -> identityFields.add("scratch-environment:" + value));
        configuration.extraPathPolicies().stream()
                .map(CliConfiguration.ExtraPathPolicy::id)
                .sorted()
                .forEach(value -> identityFields.add("path-policy:" + value));
        String version = "1-"
                + io.haifa.agent.sandbox.api.SandboxConfigurationDigest.sha256Fields(identityFields)
                        .value()
                        .substring("sha256:".length());
        SandboxProfileRef reference = new SandboxProfileRef("cli-" + provider.providerId(), version);
        Set<String> allowedEnvironment = java.util.stream.Stream.concat(
                        configuration.inheritEnvironment().stream(),
                        CodingToolchainEnvironmentProfile.defaultScratchSpace().environmentNames().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (provider.providerId().equals(HostGuardedSandboxProvider.PROVIDER_ID)) {
            return SandboxProfile.hostGuarded(
                    reference, provider.configurationDigest(), Set.of(), allowedEnvironment, true);
        }
        return new SandboxProfile(
                reference,
                provider.providerId(),
                provider.configurationDigest(),
                Set.of(),
                allowedEnvironment,
                true,
                network,
                new SandboxFilesystemPolicy(
                        SandboxWorkspaceAccess.READ_WRITE,
                        true,
                        configuration.extraPathPolicies().stream()
                                .map(CliConfiguration.ExtraPathPolicy::id)
                                .collect(java.util.stream.Collectors.toUnmodifiableSet())),
                new SandboxCapabilities(true, true, network == NetworkPolicy.DENY, false, false));
    }

    private static Map<String, String> environment(CliConfiguration.Execution configuration, SandboxProfile profile) {
        var values = new LinkedHashMap<String, String>();
        configuration.inheritEnvironment().stream().sorted().forEach(name -> {
            if (profile.providerId().equals(LocalNativeSandboxProvider.PROVIDER_ID)
                    && Set.of("HOME", "USERPROFILE", "TMPDIR", "TMP", "TEMP", "GOTMPDIR", "GOCACHE")
                            .contains(name)) {
                return;
            }
            String value = System.getenv(name);
            if (value != null) values.put(name, value);
        });
        return Map.copyOf(values);
    }

    static IllegalArgumentException diagnostic(CliConfiguration.Execution configuration, SandboxException exception) {
        if (exception.code().equals("SANDBOX_ADAPTER_UNAVAILABLE")
                && configuration.provider().equals(LocalNativeSandboxProvider.PROVIDER_ID)) {
            return new IllegalArgumentException(
                    "SANDBOX_ADAPTER_UNAVAILABLE: local-native is not implemented or its OS adapter "
                            + "failed preflight on this platform; for an explicitly trusted workspace only, "
                            + "configure execution.provider: host-guarded and execution.network: allow");
        }
        return new IllegalArgumentException(exception.code() + ": " + exception.getMessage());
    }

    static String securitySummary(SandboxProfile profile, SandboxPreflight preflight) {
        String digest = profile.contentDigest().value().substring(0, 12);
        if (profile.providerId().equals(HostGuardedSandboxProvider.PROVIDER_ID)) {
            return "provider=host-guarded (trusted local development), adapter="
                    + preflight.adapterId()
                    + ", network=ALLOW (ordinary local network: host loopback/LAN/internet may be reachable), "
                    + "current OS user, workspace/outside files/network/CPU/memory/kernel are not strongly isolated, "
                    + "approval is not isolation, profile="
                    + digest;
        }
        return "provider=local-native, adapter="
                + preflight.adapterId()
                + ", workspace="
                + profile.filesystemPolicy().workspaceAccess()
                + ", network="
                + profile.networkPolicy()
                + ", credentials=none, CPU/memory/kernel not strongly isolated, profile="
                + digest;
    }

    static final class CliOutputObserver implements ExecutionOutputObserver {
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

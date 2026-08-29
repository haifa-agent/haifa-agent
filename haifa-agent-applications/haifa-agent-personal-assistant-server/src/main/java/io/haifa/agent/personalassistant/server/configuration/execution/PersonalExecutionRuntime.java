package io.haifa.agent.personalassistant.server.configuration.execution;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.id.UuidV7IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.execution.api.ExecutionEnvironmentRef;
import io.haifa.agent.execution.api.ExecutionOutputObserver;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.execution.core.DefaultExecutionBroker;
import io.haifa.agent.execution.core.ImmutableSandboxProfileRegistry;
import io.haifa.agent.execution.core.ImmutableSandboxProviderRegistry;
import io.haifa.agent.execution.core.PolicyDecisionExecutionPolicy;
import io.haifa.agent.execution.core.change.LocalIncrementalWorkspaceChangeObserver;
import io.haifa.agent.execution.core.store.InMemoryExecutionOutputStore;
import io.haifa.agent.execution.core.store.InMemoryExecutionStore;
import io.haifa.agent.execution.core.tool.ExecutionInvocationScopeResolver.ExecutionInvocationScope;
import io.haifa.agent.execution.core.tool.ExecutionToolConfiguration;
import io.haifa.agent.execution.core.tool.ExecutionToolProvider;
import io.haifa.agent.execution.core.tool.ScriptRuntimeResolver;
import io.haifa.agent.personalassistant.application.execution.PersonalExecutionPlatform;
import io.haifa.agent.personalassistant.server.configuration.product.PersonalAssistantProperties;
import io.haifa.agent.policy.api.ApprovalVerification;
import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.changeset.FileChangeSetService;
import io.haifa.agent.project.changeset.InMemoryFileChangeSetStore;
import io.haifa.agent.project.changeset.ObservedFileChangeService;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.provider.local.LocalWorkspaceFileService;
import io.haifa.agent.project.provider.local.LocalWorkspaceLocationStore;
import io.haifa.agent.project.provider.local.SensitivePathPolicy;
import io.haifa.agent.project.store.InMemoryWorkspaceBindingStore;
import io.haifa.agent.project.store.InMemoryWorkspaceStore;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceCapabilitySet;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspacePermissionSet;
import io.haifa.agent.project.workspace.WorkspacePurpose;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import io.haifa.agent.project.workspace.WorkspaceRoot;
import io.haifa.agent.sandbox.api.SandboxConfigurationDigest;
import io.haifa.agent.sandbox.api.SandboxProfile;
import io.haifa.agent.sandbox.host.HostExecutionEnvironmentResolver;
import io.haifa.agent.sandbox.host.HostGuardedSandboxProvider;
import io.haifa.agent.sandbox.host.HostShell;
import io.haifa.agent.sandbox.host.ResolvedHostEnvironment;
import io.haifa.agent.sdk.contribution.PolicyPlatformContribution;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Server-owned host execution assembly for the private Personal Assistant workspace. */
public final class PersonalExecutionRuntime {
    private PersonalExecutionRuntime() {}

    public static PersonalExecutionPlatform create(
            Path dataDirectory,
            PrincipalRef principal,
            PersonalAssistantProperties.Execution properties,
            PolicyPlatformContribution policy,
            Clock clock) {
        Path workspaceRoot = prepare(dataDirectory.resolve("execution-workspace"));
        Path scratchRoot = Path.of(System.getProperty("java.io.tmpdir"), "haifa-agent-host-scratch")
                .toAbsolutePath()
                .normalize();
        IdentifierGenerator identifiers = new UuidV7IdentifierGenerator();
        TimeProvider time = clock::instant;
        var workspaces = new InMemoryWorkspaceStore();
        var bindings = new InMemoryWorkspaceBindingStore();
        var locations = new LocalWorkspaceLocationStore();
        WorkspaceId workspaceId = new WorkspaceId("personal-execution");
        WorkspaceBindingId bindingId = new WorkspaceBindingId("personal-execution-binding");
        WorkspaceLocationRef locationRef = new WorkspaceLocationRef("personal-execution-location");
        locations.register(locationRef, workspaceRoot);
        bindings.create(WorkspaceBinding.provision(
                        bindingId,
                        locationRef,
                        WorkspaceBindingMode.DIRECT,
                        principal,
                        new WorkspaceCapabilitySet(Set.of("execution.run", "workspace.write")),
                        WorkspacePermissionSet.readWriteExecute(),
                        LocalWorkspaceLocationStore.fingerprintFor(workspaceRoot),
                        time.now())
                .activate(time.now()));
        workspaces.create(Workspace.provision(
                        workspaceId,
                        new ProjectId("personal-internal-execution"),
                        WorkspacePurpose.PRIMARY,
                        new WorkspaceRoot(ProjectPath.root(), bindingId, "personal-host-guarded"),
                        WorkspaceRevision.initial("personal-execution-v1"),
                        time.now())
                .activate(time.now()));

        HostShell shell = HostShell.auto();
        var host =
                new HostGuardedSandboxProvider(workspaces, bindings, locations, identifiers, time, shell, scratchRoot);
        ScriptRuntimeResolver runtimes = ScriptRuntimeResolver.currentHost(
                configuredPath(properties.pythonPath()), configuredPath(properties.powerShellPath()));
        var resolvedEnvironment = resolveHostEnvironment(
                System.getenv(),
                System.getProperty("os.name", ""),
                Path.of(System.getProperty("user.home", ".")),
                dataDirectory,
                workspaceRoot,
                scratchRoot);
        Map<String, String> environment = resolvedEnvironment.environment();
        Set<String> environmentNames = resolvedEnvironment.allowedEnvironmentNames();
        String profileVersion = "3-"
                + SandboxConfigurationDigest.sha256Fields(List.of(
                                host.configurationDigest().value(),
                                HostExecutionEnvironmentResolver.POLICY_VERSION,
                                PersonalWorkspaceChangeIgnorePolicy.VERSION))
                        .value()
                        .substring("sha256:".length());
        SandboxProfile profile = SandboxProfile.hostGuarded(
                new SandboxProfileRef("personal-host-guarded", profileVersion),
                host.configurationDigest(),
                runtimes.executableNames(),
                environmentNames,
                true);
        host.preflight(profile);
        var files = new LocalWorkspaceFileService(workspaces, bindings, locations, SensitivePathPolicy.defaults());
        var changeSetStore = new InMemoryFileChangeSetStore();
        var changeSets = new FileChangeSetService(changeSetStore, identifiers, time);
        var workspaceChanges = new LocalIncrementalWorkspaceChangeObserver(
                workspaceId, workspaceRoot, new PersonalWorkspaceChangeIgnorePolicy());
        var broker = new DefaultExecutionBroker(
                new InMemoryExecutionStore(),
                new InMemoryExecutionOutputStore(),
                ignored -> io.haifa.agent.execution.api.ResolvedExecutionEnvironment.of(environment),
                new PolicyDecisionExecutionPolicy(
                        policy.decisions(), policy.snapshots(), policy.authorizationEvidence(), clock),
                new ImmutableSandboxProfileRegistry(List.of(profile)),
                new ImmutableSandboxProviderRegistry(List.of(host)),
                workspaces,
                bindings,
                workspaceChanges,
                new ObservedFileChangeService(workspaces, changeSetStore, changeSets, time));
        var configuration = new ExecutionToolConfiguration(
                new ExecutionEnvironmentRef(
                        List.of("personal-execution-" + profile.contentDigest().value())),
                profile.ref(),
                Duration.ofMillis(properties.defaultTimeoutMillis()),
                Duration.ofMillis(properties.maximumTimeoutMillis()),
                properties.maximumOutputBytes(),
                properties.maximumOutputLines(),
                properties.maximumProcesses(),
                false,
                runtimes,
                ExecutionOutputObserver.noop(),
                java.util.function.UnaryOperator.identity());
        var provider = new ExecutionToolProvider(
                broker,
                identifiers,
                time,
                ignored -> new ExecutionInvocationScope(workspaceId, Set.of("execution.run", "workspace.write")),
                configuration,
                (resolvedWorkspaceId, inputPaths) -> inputPaths.forEach(path ->
                        files.stat(new io.haifa.agent.project.path.WorkspacePath(resolvedWorkspaceId, path), false)));
        try {
            return PersonalExecutionPlatform.create(
                    provider,
                    profile,
                    runtimes,
                    (request, responder) -> {
                        boolean samePrincipal = request.requester().tenant().equals(responder.tenant())
                                && request.requester().principal().equals(responder.principal());
                        return new ApprovalVerification(
                                samePrincipal, samePrincipal ? "LOCAL_PRINCIPAL_MATCH" : "LOCAL_PRINCIPAL_MISMATCH");
                    },
                    workspaceChanges);
        } catch (RuntimeException | Error exception) {
            workspaceChanges.close();
            throw exception;
        }
    }

    private static Optional<Path> configuredPath(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path) || !Files.isExecutable(path)) {
            throw new IllegalArgumentException("configured script runtime is not an executable file");
        }
        return Optional.of(path);
    }

    static ResolvedHostEnvironment resolveHostEnvironment(
            Map<String, String> hostEnvironment,
            String operatingSystem,
            Path jvmUserHome,
            Path applicationDataRoot,
            Path workspaceRoot,
            Path scratchRoot) {
        return HostExecutionEnvironmentResolver.resolveHostUser(
                hostEnvironment,
                operatingSystem,
                jvmUserHome,
                applicationDataRoot,
                workspaceRoot,
                scratchRoot,
                Set.of());
    }

    private static Path prepare(Path value) {
        try {
            Files.createDirectories(value);
            return value.toRealPath();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Personal execution workspace is unavailable", exception);
        }
    }
}

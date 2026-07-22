package io.haifa.agent.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.ExecutionCommandMode;
import io.haifa.agent.execution.api.ExecutionEnvironmentRef;
import io.haifa.agent.execution.api.ExecutionId;
import io.haifa.agent.execution.api.ExecutionLimits;
import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.execution.api.ExecutionStatus;
import io.haifa.agent.execution.api.ManagedProcessRequest;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.execution.api.TrustedExecutionContext;
import io.haifa.agent.execution.core.DefaultExecutionBroker;
import io.haifa.agent.execution.core.manifest.ManifestBudget;
import io.haifa.agent.execution.core.manifest.ManifestDiffService;
import io.haifa.agent.execution.core.manifest.WorkspaceManifestService;
import io.haifa.agent.execution.core.store.InMemoryExecutionOutputStore;
import io.haifa.agent.execution.core.store.InMemoryExecutionStore;
import io.haifa.agent.mcp.client.SdkMcpStdioClientFactory;
import io.haifa.agent.mcp.config.McpConnectionPolicy;
import io.haifa.agent.mcp.config.McpProtocolProfile;
import io.haifa.agent.mcp.config.McpServerDefinition;
import io.haifa.agent.mcp.config.McpServerId;
import io.haifa.agent.mcp.config.McpToolImportPolicy;
import io.haifa.agent.mcp.config.StdioDefinition;
import io.haifa.agent.mcp.transport.stdio.McpManagedProcessLaunch;
import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.changeset.FileChangeSetService;
import io.haifa.agent.project.changeset.InMemoryFileChangeSetStore;
import io.haifa.agent.project.changeset.ObservedFileChangeService;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.path.WorkspacePath;
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
import io.haifa.agent.sandbox.api.NetworkPolicy;
import io.haifa.agent.sandbox.api.SandboxProfile;
import io.haifa.agent.sandbox.host.HostGuardedSandboxProvider;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostStdioMcpComponentTest {
    private static final Instant NOW = Instant.parse("2026-07-22T00:00:00Z");

    @TempDir
    Path root;

    @Test
    void runsMcpStdioThroughDefaultBrokerAndGuardedHostProvider() throws Exception {
        copyStubClass(root);
        Fixture fixture = fixture(root);
        var executionId = new ExecutionId("mcp-stdio-host");
        AtomicBoolean bindingClosed = new AtomicBoolean();
        var factory = new SdkMcpStdioClientFactory(
                fixture.broker(),
                (definition, identity, credentials) -> new McpManagedProcessLaunch(
                        managedRequest(fixture.workspaceId(), executionId), () -> bindingClosed.set(true)));
        var client = factory.create(stdioServer(), McpTestFixtures.IDENTITY);

        var initialized = client.initialize(List.of());
        var tools = client.listTools(null, List.of());
        var result = client.callTool(
                "echo", Map.of("value", "hello"), List.of(), io.haifa.agent.tool.api.ToolInvocationObserver.noop());
        client.close();

        assertThat(initialized.negotiatedProtocolVersion()).isEqualTo(McpProtocolProfile.VERSION_2025_11_25);
        assertThat(tools.tools()).extracting(tool -> tool.name()).containsExactly("echo");
        assertThat(result.structuredContent()).containsEntry("value", "hello");
        assertThat(bindingClosed).isTrue();
        var execution = awaitExecution(fixture.broker(), executionId);
        assertThat(execution.status()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(execution.optionalFailure())
                .get()
                .extracting(failure -> failure.code())
                .isEqualTo("CANCELLED");
        assertThat(execution.stdout().summary()).contains("host-stdio-stub");
    }

    private static Fixture fixture(Path root) {
        WorkspaceId workspaceId = new WorkspaceId("mcp-host-workspace");
        var workspaces = new InMemoryWorkspaceStore();
        var bindings = new InMemoryWorkspaceBindingStore();
        var locations = new LocalWorkspaceLocationStore();
        var location = new WorkspaceLocationRef("mcp-host-location");
        locations.register(location, root);
        var binding = WorkspaceBinding.provision(
                        new WorkspaceBindingId("mcp-host-binding"),
                        location,
                        WorkspaceBindingMode.DIRECT,
                        new PrincipalRef("owner", "user"),
                        WorkspaceCapabilitySet.executionFiles(),
                        WorkspacePermissionSet.readWriteExecute(),
                        LocalWorkspaceLocationStore.fingerprintFor(root),
                        NOW)
                .activate(NOW);
        bindings.create(binding);
        workspaces.create(Workspace.provision(
                        workspaceId,
                        new ProjectId("mcp-host-project"),
                        WorkspacePurpose.PRIMARY,
                        new WorkspaceRoot(ProjectPath.root(), binding.id(), "test"),
                        WorkspaceRevision.initial(binding.rootFingerprint()),
                        NOW)
                .activate(NOW));
        var files = new LocalWorkspaceFileService(workspaces, bindings, locations, SensitivePathPolicy.defaults());
        var manifests = new WorkspaceManifestService(
                workspaces, files, new ManifestBudget(100, 1024 * 1024, 1024 * 1024), "mcp-host-test-v1");
        var changeSets = new InMemoryFileChangeSetStore();
        var ids = new AtomicInteger();
        var changeSetService = new FileChangeSetService(changeSets, () -> "change-" + ids.incrementAndGet(), () -> NOW);
        var observed = new ObservedFileChangeService(workspaces, changeSets, changeSetService, () -> NOW);
        var profile = new SandboxProfile(
                new SandboxProfileRef("host-guarded", "1"), Set.of("java"), Set.of(), false, NetworkPolicy.ALLOW);
        var host = new HostGuardedSandboxProvider(
                workspaces, bindings, locations, () -> "host-session-" + ids.incrementAndGet(), Instant::now);
        var broker = new DefaultExecutionBroker(
                new InMemoryExecutionStore(),
                new InMemoryExecutionOutputStore(),
                ignored -> Map.of(),
                ignored -> {},
                ignored -> profile,
                ignored -> host,
                workspaces,
                bindings,
                manifests,
                new ManifestDiffService(),
                observed);
        return new Fixture(workspaceId, broker);
    }

    private static ManagedProcessRequest managedRequest(WorkspaceId workspaceId, ExecutionId executionId) {
        return new ManagedProcessRequest(new ExecutionRequest(
                executionId,
                "mcp-stdio-host-key",
                new TrustedExecutionContext("mcp-control", McpTestFixtures.PRINCIPAL, Set.of("execution.run"), "allow"),
                workspaceId,
                WorkspacePath.root(workspaceId),
                new ExecutionCommand(
                        ExecutionCommandMode.DIRECT,
                        List.of("java", "-cp", ".", "io.haifa.agent.mcp.HostStdioStubMain")),
                new ExecutionEnvironmentRef(List.of()),
                new ExecutionLimits(Duration.ofSeconds(10), 64 * 1024, 16 * 1024, 2),
                new SandboxProfileRef("host-guarded", "1")));
    }

    private static McpServerDefinition stdioServer() {
        return McpServerDefinition.create(
                new McpServerId("host-stdio"),
                "Host stdio stub",
                true,
                McpProtocolProfile.FIXED_2025_11_25,
                new StdioDefinition(
                        "java",
                        List.of("-cp", ".", "io.haifa.agent.mcp.HostStdioStubMain"),
                        "project",
                        Set.of(),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(2),
                        64 * 1024,
                        16 * 1024),
                new McpToolImportPolicy(Set.of("echo"), Set.of(), "host", Map.of(), Map.of(), Map.of(), Map.of()),
                new McpConnectionPolicy(
                        Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofSeconds(2), 0),
                List.of(),
                "1.0.0");
    }

    private static void copyStubClass(Path root) throws Exception {
        Path destination = root.resolve("io/haifa/agent/mcp/HostStdioStubMain.class");
        Files.createDirectories(destination.getParent());
        try (InputStream source =
                HostStdioStubMain.class.getResourceAsStream("/io/haifa/agent/mcp/HostStdioStubMain.class")) {
            if (source == null) throw new IllegalStateException("compiled Host stdio stub is unavailable");
            Files.copy(source, destination);
        }
    }

    private static io.haifa.agent.execution.api.ExecutionResult awaitExecution(
            DefaultExecutionBroker broker, ExecutionId executionId) throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            var result = broker.find(executionId);
            if (result.isPresent()) return result.orElseThrow();
            Thread.sleep(10);
        }
        throw new AssertionError("managed execution result was not completed");
    }

    private record Fixture(WorkspaceId workspaceId, DefaultExecutionBroker broker) {}
}

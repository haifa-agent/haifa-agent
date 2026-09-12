package io.haifa.agent.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.execution.api.ExecutionBroker;
import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.ExecutionCommandMode;
import io.haifa.agent.execution.api.ExecutionEnvironmentRef;
import io.haifa.agent.execution.api.ExecutionId;
import io.haifa.agent.execution.api.ExecutionLimits;
import io.haifa.agent.execution.api.ExecutionOrigin;
import io.haifa.agent.execution.api.ExecutionOutputChannel;
import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.execution.api.ExecutionResult;
import io.haifa.agent.execution.api.ExecutionStatus;
import io.haifa.agent.execution.api.ManagedProcessRequest;
import io.haifa.agent.execution.api.ManagedProcessSession;
import io.haifa.agent.execution.api.ManagedProcessSessionId;
import io.haifa.agent.execution.api.ProcessExit;
import io.haifa.agent.execution.api.ProcessInputChunk;
import io.haifa.agent.execution.api.ProcessOutputChunk;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.execution.api.TrustedExecutionContext;
import io.haifa.agent.mcp.client.McpClientFacade;
import io.haifa.agent.mcp.client.SdkMcpClientFactory;
import io.haifa.agent.mcp.client.SdkMcpStdioClientFactory;
import io.haifa.agent.mcp.config.McpConnectionPolicy;
import io.haifa.agent.mcp.config.McpProtocolProfile;
import io.haifa.agent.mcp.config.McpServerDefinition;
import io.haifa.agent.mcp.config.McpServerId;
import io.haifa.agent.mcp.config.McpToolImportPolicy;
import io.haifa.agent.mcp.config.StdioDefinition;
import io.haifa.agent.mcp.protocol.McpRemoteContent;
import io.haifa.agent.mcp.transport.stdio.McpManagedProcessLaunch;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.tool.api.ToolInvocationObserver;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OfficialDualEraMcpCompatibilityLiveIT {
    @ParameterizedTest(name = "HTTP {0}")
    @MethodSource("supportedVersions")
    void callsOfficialDualEraHelloWorldOverHttp(String version, McpProtocolProfile protocol) {
        String endpointValue = System.getenv("HAIFA_OFFICIAL_MCP_HTTP_URL");
        Assumptions.assumeTrue(endpointValue != null && !endpointValue.isBlank());
        var server = McpTestFixtures.httpServer(URI.create(endpointValue), Set.of("greet"), protocol);
        var client = new SdkMcpClientFactory().create(server, McpTestFixtures.IDENTITY);

        try {
            assertHelloWorld(client, version);
        } finally {
            client.close();
        }
    }

    @ParameterizedTest(name = "stdio {0}")
    @MethodSource("supportedVersions")
    void callsOfficialDualEraHelloWorldOverStdio(String version, McpProtocolProfile protocol) {
        String repositoryValue = System.getenv("HAIFA_OFFICIAL_MCP_REPO");
        Assumptions.assumeTrue(repositoryValue != null && !repositoryValue.isBlank());
        Path repository = Path.of(repositoryValue).toAbsolutePath().normalize();
        List<String> command = isWindows()
                ? List.of("cmd.exe", "/d", "/s", "/c", "pnpm", "tsx", "examples/dual-era/server.ts")
                : List.of("pnpm", "tsx", "examples/dual-era/server.ts");
        var broker = new LocalProcessExecutionBroker(repository);
        var server = stdioServer(protocol, command);
        AtomicInteger sequence = new AtomicInteger();
        var factory = new SdkMcpStdioClientFactory(
                broker,
                (definition, identity, credentials) ->
                        new McpManagedProcessLaunch(processRequest(command, sequence.incrementAndGet()), () -> {}));
        var client = factory.create(server, McpTestFixtures.IDENTITY);

        try {
            assertHelloWorld(client, version);
        } finally {
            client.close();
        }
    }

    private static void assertHelloWorld(McpClientFacade client, String version) {
        var snapshot = client.initialize(Map.of());
        var tools = client.listTools(null, Map.of());
        var result = client.callTool("greet", Map.of("name", version), Map.of(), ToolInvocationObserver.noop());

        assertThat(snapshot.targetProtocolVersion()).isEqualTo(version);
        assertThat(snapshot.negotiatedProtocolVersion()).isEqualTo(version);
        assertThat(tools.tools()).extracting(tool -> tool.name()).containsExactly("greet");
        assertThat(result.error()).isFalse();
        assertThat(result.content())
                .filteredOn(content -> content.kind() == McpRemoteContent.Kind.TEXT)
                .extracting(McpRemoteContent::text)
                .singleElement()
                .asString()
                .contains("Hello, " + version + "!");
    }

    private static McpServerDefinition stdioServer(McpProtocolProfile protocol, List<String> command) {
        return McpServerDefinition.create(
                new McpServerId("official-dual-era"),
                "Official dual-era MCP example",
                true,
                protocol,
                new StdioDefinition(
                        command.getFirst(),
                        command.subList(1, command.size()),
                        "official-sdk",
                        Set.of(),
                        java.time.Duration.ofSeconds(10),
                        java.time.Duration.ofSeconds(10),
                        java.time.Duration.ofSeconds(30),
                        java.time.Duration.ofSeconds(5),
                        1024 * 1024,
                        64 * 1024),
                new McpToolImportPolicy(Set.of("greet"), Set.of(), "official", Map.of(), Map.of(), Map.of(), Map.of()),
                new McpConnectionPolicy(
                        java.time.Duration.ofSeconds(10),
                        java.time.Duration.ofSeconds(10),
                        java.time.Duration.ofSeconds(30),
                        java.time.Duration.ofSeconds(5),
                        0),
                List.of(),
                "1.0.0");
    }

    private static ManagedProcessRequest processRequest(List<String> command, int sequence) {
        WorkspaceId workspaceId = new WorkspaceId("official-mcp-live");
        return new ManagedProcessRequest(new ExecutionRequest(
                new ExecutionId("official-mcp-live-" + sequence),
                "official-mcp-live-" + sequence,
                new TrustedExecutionContext(
                        McpTestFixtures.TENANT,
                        "mcp-control",
                        McpTestFixtures.PRINCIPAL,
                        Set.of("execution_run"),
                        ExecutionOrigin.PRODUCT_INTERNAL,
                        Optional.empty()),
                workspaceId,
                WorkspacePath.root(workspaceId),
                new ExecutionCommand(ExecutionCommandMode.DIRECT, command),
                new ExecutionEnvironmentRef(List.of("inherited-test-environment")),
                new ExecutionLimits(java.time.Duration.ofSeconds(30), 1024 * 1024, 64 * 1024, 1),
                new SandboxProfileRef("local-live-test", "1")));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static Stream<Arguments> supportedVersions() {
        return Stream.of(
                Arguments.of(McpProtocolProfile.VERSION_2025_03_26, McpProtocolProfile.FIXED_2025_03_26),
                Arguments.of(McpProtocolProfile.VERSION_2025_06_18, McpProtocolProfile.FIXED_2025_06_18),
                Arguments.of(McpProtocolProfile.VERSION_2025_11_25, McpProtocolProfile.FIXED_2025_11_25),
                Arguments.of(McpProtocolProfile.VERSION_2026_07_28, McpProtocolProfile.FIXED_2026_07_28));
    }

    private static final class LocalProcessExecutionBroker implements ExecutionBroker {
        private final Path workingDirectory;

        private LocalProcessExecutionBroker(Path workingDirectory) {
            this.workingDirectory = workingDirectory;
        }

        @Override
        public ManagedProcessSession openManagedSession(ManagedProcessRequest request) {
            try {
                return new LocalProcessSession(
                        request.execution().id(), request.execution().command().argv(), workingDirectory);
            } catch (IOException exception) {
                throw new IllegalStateException("failed to start official MCP example", exception);
            }
        }

        @Override
        public ExecutionResult execute(ExecutionRequest request) {
            throw new UnsupportedOperationException("one-shot execution is not used by this live test");
        }

        @Override
        public boolean cancel(ExecutionId id) {
            return false;
        }

        @Override
        public Optional<ExecutionResult> find(ExecutionId id) {
            return Optional.empty();
        }
    }

    private static final class LocalProcessSession implements ManagedProcessSession {
        private final ManagedProcessSessionId id;
        private final Process process;
        private final BlockingQueue<ProcessOutputChunk> output = new LinkedBlockingQueue<>();
        private final CompletableFuture<ProcessExit> exit;
        private final AtomicBoolean closed = new AtomicBoolean();

        private LocalProcessSession(ExecutionId executionId, List<String> command, Path workingDirectory)
                throws IOException {
            id = new ManagedProcessSessionId(executionId.value());
            process = new ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .start();
            Thread.ofVirtual().start(() -> pump(process.getInputStream(), ExecutionOutputChannel.STDOUT));
            Thread.ofVirtual().start(() -> pump(process.getErrorStream(), ExecutionOutputChannel.STDERR));
            exit = process.onExit()
                    .thenApply(completed -> new ProcessExit(
                            ExecutionStatus.EXITED, completed.exitValue(), true, java.time.Instant.now()));
        }

        @Override
        public ManagedProcessSessionId id() {
            return id;
        }

        @Override
        public synchronized void write(ProcessInputChunk input) {
            try {
                process.getOutputStream().write(input.bytes());
                process.getOutputStream().flush();
            } catch (IOException exception) {
                throw new IllegalStateException("failed to write to official MCP example", exception);
            }
        }

        @Override
        public Optional<ProcessOutputChunk> read(java.time.Duration timeout) {
            try {
                return Optional.ofNullable(output.poll(timeout.toMillis(), TimeUnit.MILLISECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while reading official MCP example", exception);
            }
        }

        @Override
        public CompletableFuture<ProcessExit> exit() {
            return exit;
        }

        @Override
        public boolean cancel() {
            if (!process.isAlive()) return false;
            process.destroy();
            return true;
        }

        @Override
        public boolean isClosed() {
            return closed.get();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            process.destroy();
        }

        private void pump(InputStream source, ExecutionOutputChannel channel) {
            try (source) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = source.read(buffer)) >= 0) {
                    if (length > 0) {
                        output.add(
                                new ProcessOutputChunk(channel, java.util.Arrays.copyOf(buffer, length), false, false));
                    }
                }
            } catch (IOException exception) {
                if (!closed.get()) {
                    output.add(new ProcessOutputChunk(
                            ExecutionOutputChannel.STDERR,
                            exception.getMessage().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            false,
                            false));
                }
            }
        }
    }
}

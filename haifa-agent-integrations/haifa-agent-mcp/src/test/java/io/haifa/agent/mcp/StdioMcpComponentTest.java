package io.haifa.agent.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.credential.api.CredentialDefinitionId;
import io.haifa.agent.credential.api.CredentialExposureMode;
import io.haifa.agent.credential.api.CredentialRequirement;
import io.haifa.agent.execution.api.ExecutionBroker;
import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.ExecutionCommandMode;
import io.haifa.agent.execution.api.ExecutionEnvironmentRef;
import io.haifa.agent.execution.api.ExecutionId;
import io.haifa.agent.execution.api.ExecutionLimits;
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
import io.haifa.agent.mcp.client.SdkMcpStdioClientFactory;
import io.haifa.agent.mcp.config.McpConnectionPolicy;
import io.haifa.agent.mcp.config.McpCredentialInjection;
import io.haifa.agent.mcp.config.McpProtocolProfile;
import io.haifa.agent.mcp.config.McpServerDefinition;
import io.haifa.agent.mcp.config.McpServerId;
import io.haifa.agent.mcp.config.McpToolImportPolicy;
import io.haifa.agent.mcp.config.StdioDefinition;
import io.haifa.agent.mcp.transport.stdio.McpManagedProcessLaunch;
import io.haifa.agent.mcp.transport.stdio.McpStdioEnvironmentRegistry;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StdioMcpComponentTest {
    @Test
    void bridgesJsonRpcThroughExecutionBrokerManagedSessionAndClosesProcessOwner() {
        var server = stdioServer(List.of());
        var broker = new StubExecutionBroker();
        AtomicBoolean bindingClosed = new AtomicBoolean();
        var factory = new SdkMcpStdioClientFactory(
                broker,
                (definition, identity, credentials) -> new McpManagedProcessLaunch(
                        request(new ExecutionEnvironmentRef(List.of())), () -> bindingClosed.set(true)));
        var client = factory.create(server, McpTestFixtures.IDENTITY);

        var snapshot = client.initialize(List.of());
        var tools = client.listTools(null, List.of());
        AtomicInteger dispatched = new AtomicInteger();
        var result = client.callTool("echo", Map.of("value", "hello"), List.of(), observer(dispatched));
        client.close();

        assertThat(snapshot.negotiatedProtocolVersion()).isEqualTo("2025-11-25");
        assertThat(tools.tools()).extracting(tool -> tool.name()).containsExactly("echo");
        assertThat(result.structuredContent()).containsEntry("value", "hello");
        assertThat(dispatched).hasValue(1);
        assertThat(broker.session.closed).isTrue();
        assertThat(bindingClosed).isTrue();
        assertThat(broker.opened).isTrue();
    }

    private static io.haifa.agent.tool.api.ToolInvocationObserver observer(AtomicInteger dispatched) {
        return new io.haifa.agent.tool.api.ToolInvocationObserver() {
            @Override
            public void dispatched() {
                dispatched.incrementAndGet();
            }

            @Override
            public void acknowledged() {}
        };
    }

    @Test
    void materializesOnlyAllowlistedEnvironmentCredentialsAndRevokesBinding() {
        var registry = new McpStdioEnvironmentRegistry(() -> "env-binding");
        var requirement = new CredentialRequirement(
                new CredentialDefinitionId("utility-token"),
                "utility",
                Set.of("mcp:tools:call"),
                CredentialExposureMode.ENVIRONMENT_VARIABLE);
        var injection = new McpCredentialInjection(requirement, "UTILITY_TOKEN", "Bearer ");
        var lease = McpTestFixtures.lease("credential-binding", "secret-value");
        var binding = registry.bind(List.of(injection), List.of(lease), Set.of("UTILITY_TOKEN"));

        assertThat(registry.resolve(binding.reference())).containsEntry("UTILITY_TOKEN", "Bearer secret-value");
        binding.close();
        assertThatThrownBy(() -> registry.resolve(binding.reference())).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> registry.bind(List.of(injection), List.of(lease), Set.of("OTHER")))
                .isInstanceOf(SecurityException.class);
    }

    private static McpServerDefinition stdioServer(List<McpCredentialInjection> credentials) {
        return McpServerDefinition.create(
                new McpServerId("stdio"),
                "Stdio stub",
                true,
                McpProtocolProfile.FIXED_2025_11_25,
                new StdioDefinition(
                        "stub-mcp",
                        List.of("--stdio"),
                        "project",
                        credentials.stream()
                                .map(McpCredentialInjection::targetName)
                                .collect(java.util.stream.Collectors.toSet()),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(2),
                        64 * 1024,
                        16 * 1024),
                new McpToolImportPolicy(Set.of("echo"), Set.of(), "stdio", Map.of(), Map.of(), Map.of(), Map.of()),
                new McpConnectionPolicy(
                        Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofSeconds(2), 0),
                credentials,
                "1.0.0");
    }

    private static ManagedProcessRequest request(ExecutionEnvironmentRef environment) {
        WorkspaceId workspace = new WorkspaceId("workspace");
        return new ManagedProcessRequest(new ExecutionRequest(
                new ExecutionId("mcp-stdio-test"),
                "mcp-stdio-test",
                new TrustedExecutionContext("mcp-control", McpTestFixtures.PRINCIPAL, Set.of("execution.run"), "allow"),
                workspace,
                WorkspacePath.root(workspace),
                new ExecutionCommand(ExecutionCommandMode.DIRECT, List.of("stub-mcp", "--stdio")),
                environment,
                new ExecutionLimits(Duration.ofSeconds(10), 64 * 1024, 16 * 1024, 1),
                new SandboxProfileRef("host-guarded", "1")));
    }

    private static final class StubExecutionBroker implements ExecutionBroker {
        private final StubSession session = new StubSession();
        private final AtomicBoolean opened = new AtomicBoolean();

        @Override
        public ExecutionResult execute(ExecutionRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ManagedProcessSession openManagedSession(ManagedProcessRequest request) {
            opened.set(true);
            return session;
        }

        @Override
        public boolean cancel(ExecutionId id) {
            return session.cancel();
        }

        @Override
        public Optional<ExecutionResult> find(ExecutionId id) {
            return Optional.empty();
        }
    }

    private static final class StubSession implements ManagedProcessSession {
        private final ObjectMapper mapper = new ObjectMapper();
        private final LinkedBlockingQueue<ProcessOutputChunk> output = new LinkedBlockingQueue<>();
        private final CompletableFuture<ProcessExit> exit = new CompletableFuture<>();
        private volatile boolean closed;

        @Override
        public ManagedProcessSessionId id() {
            return new ManagedProcessSessionId("stub-session");
        }

        @Override
        public void write(ProcessInputChunk input) {
            try {
                String frame = new String(input.bytes(), StandardCharsets.UTF_8).trim();
                Map<String, Object> request = mapper.readValue(frame, new TypeReference<>() {});
                if (!request.containsKey("id")) return;
                String method = String.valueOf(request.get("method"));
                Object result =
                        switch (method) {
                            case "initialize" ->
                                Map.of(
                                        "protocolVersion", "2025-11-25",
                                        "capabilities", Map.of("tools", Map.of("listChanged", false)),
                                        "serverInfo", Map.of("name", "stdio-stub", "version", "1.0.0"));
                            case "tools/list" ->
                                Map.of(
                                        "tools",
                                        List.of(Map.of(
                                                "name",
                                                "echo",
                                                "description",
                                                "echo",
                                                "inputSchema",
                                                Map.of("type", "object"),
                                                "outputSchema",
                                                Map.of("type", "object"))));
                            case "tools/call" ->
                                Map.of(
                                        "content", List.of(Map.of("type", "text", "text", "hello")),
                                        "structuredContent", Map.of("value", "hello"),
                                        "isError", false);
                            default -> throw new IllegalStateException(method);
                        };
                byte[] response = (mapper.writeValueAsString(
                                        Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result))
                                + "\n")
                        .getBytes(StandardCharsets.UTF_8);
                output.add(new ProcessOutputChunk(ExecutionOutputChannel.STDOUT, response, false, false));
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public Optional<ProcessOutputChunk> read(Duration timeout) {
            try {
                return Optional.ofNullable(output.poll(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public CompletableFuture<ProcessExit> exit() {
            return exit;
        }

        @Override
        public boolean cancel() {
            close();
            return true;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            exit.complete(new ProcessExit(ExecutionStatus.CANCELLED, null, true, Instant.now()));
        }
    }
}

package io.haifa.agent.mcp.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.execution.api.ExecutionBroker;
import io.haifa.agent.mcp.config.McpServerDefinition;
import io.haifa.agent.mcp.config.StdioDefinition;
import io.haifa.agent.mcp.transport.stdio.McpManagedProcessLaunchFactory;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Objects;

public final class SdkMcpStdioClientFactory implements McpClientFactory {
    private final ExecutionBroker executionBroker;
    private final McpManagedProcessLaunchFactory launches;
    private final McpTelemetry telemetry;

    public SdkMcpStdioClientFactory(ExecutionBroker executionBroker, McpManagedProcessLaunchFactory launches) {
        this(executionBroker, launches, McpTelemetry.noop());
    }

    public SdkMcpStdioClientFactory(
            ExecutionBroker executionBroker, McpManagedProcessLaunchFactory launches, McpTelemetry telemetry) {
        this.executionBroker = Objects.requireNonNull(executionBroker, "executionBroker");
        this.launches = Objects.requireNonNull(launches, "launches");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    @Override
    public McpClientFacade create(McpServerDefinition server, McpConnectionIdentity identity) {
        if (!(server.transport() instanceof StdioDefinition)) {
            throw new IllegalArgumentException("SDK stdio factory only accepts stdio definitions");
        }
        var objectMapper = new ObjectMapper();
        var mapper = new JacksonMcpJsonMapper(objectMapper);
        var credentials = new io.haifa.agent.mcp.transport.stdio.McpStdioCredentialContext();
        var transport = new io.haifa.agent.mcp.transport.stdio.ExecutionBrokerMcpTransport(
                server, identity, executionBroker, launches, credentials, mapper);
        var trackedTransport = new TrackingMcpClientTransport(transport);
        var client = McpClient.sync(trackedTransport)
                .clientInfo(
                        McpSchema.Implementation.builder("haifa-agent", "0.1.0").build())
                .capabilities(McpSchema.ClientCapabilities.builder().build())
                .requestTimeout(server.connectionPolicy().requestTimeout())
                .initializationTimeout(server.connectionPolicy().connectTimeout())
                .transportContextProvider(credentials::snapshot)
                .build();
        return new SdkMcpClientFacade(server, client, credentials, objectMapper, telemetry, trackedTransport);
    }
}

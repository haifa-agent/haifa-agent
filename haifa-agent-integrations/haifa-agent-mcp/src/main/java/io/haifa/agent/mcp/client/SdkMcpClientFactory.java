package io.haifa.agent.mcp.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.mcp.config.McpProtocolProfile;
import io.haifa.agent.mcp.config.McpServerDefinition;
import io.haifa.agent.mcp.config.StreamableHttpDefinition;
import io.haifa.agent.mcp.transport.http.BoundedHttpClientBuilder;
import io.haifa.agent.mcp.transport.http.McpHttpCredentialContext;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public final class SdkMcpClientFactory implements McpClientFactory {
    private final Consumer<String> toolsChanged;
    private final McpTelemetry telemetry;

    public SdkMcpClientFactory() {
        this(serverId -> {}, McpTelemetry.noop());
    }

    public SdkMcpClientFactory(Consumer<String> toolsChanged) {
        this(toolsChanged, McpTelemetry.noop());
    }

    public SdkMcpClientFactory(Consumer<String> toolsChanged, McpTelemetry telemetry) {
        this.toolsChanged = Objects.requireNonNull(toolsChanged, "toolsChanged");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    @Override
    public McpClientFacade create(McpServerDefinition server, McpConnectionIdentity identity) {
        if (!(server.transport() instanceof StreamableHttpDefinition http)) {
            throw new IllegalArgumentException("SDK HTTP factory only accepts Streamable HTTP definitions");
        }
        var mapper = new JacksonMcpJsonMapper(new ObjectMapper());
        String origin = StreamableHttpDefinition.origin(http.endpoint());
        var credentials = new McpHttpCredentialContext(server.discoveryCredentials(), origin);
        String endpoint = http.endpoint().getRawPath();
        if (endpoint == null || endpoint.isBlank()) endpoint = "/mcp";
        var transport = HttpClientStreamableHttpTransport.builder(origin)
                .endpoint(endpoint)
                .jsonMapper(mapper)
                .clientBuilder(new BoundedHttpClientBuilder(
                        HttpClient.newBuilder()
                                .connectTimeout(http.connectTimeout())
                                .followRedirects(HttpClient.Redirect.NEVER),
                        http.maxBodyBytes(),
                        http.maxHeaderBytes()))
                .requestBuilder(HttpRequest.newBuilder().timeout(http.requestTimeout()))
                .resumableStreams(true)
                .openConnectionOnStartup(false)
                .supportedProtocolVersions(List.of(McpProtocolProfile.VERSION_2025_11_25))
                .httpRequestCustomizer(credentials::customize)
                .build();
        var trackedTransport = new TrackingMcpClientTransport(transport);
        var client = McpClient.sync(trackedTransport)
                .clientInfo(
                        McpSchema.Implementation.builder("haifa-agent", "0.1.0").build())
                .capabilities(McpSchema.ClientCapabilities.builder().build())
                .requestTimeout(server.connectionPolicy().requestTimeout())
                .initializationTimeout(server.connectionPolicy().connectTimeout())
                .transportContextProvider(credentials::snapshot)
                .toolsChangeConsumer(
                        ignored -> toolsChanged.accept(server.serverId().value()))
                .build();
        return new SdkMcpClientFacade(server, client, credentials, new ObjectMapper(), telemetry, trackedTransport);
    }
}

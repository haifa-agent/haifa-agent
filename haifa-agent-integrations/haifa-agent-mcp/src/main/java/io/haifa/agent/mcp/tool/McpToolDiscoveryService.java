package io.haifa.agent.mcp.tool;

import io.haifa.agent.credential.api.CredentialBroker;
import io.haifa.agent.credential.api.CredentialLease;
import io.haifa.agent.credential.api.CredentialOperation;
import io.haifa.agent.credential.api.CredentialOperationRequest;
import io.haifa.agent.mcp.client.McpConnection;
import io.haifa.agent.mcp.client.McpConnectionManager;
import io.haifa.agent.mcp.config.McpServerId;
import io.haifa.agent.mcp.config.StdioDefinition;
import io.haifa.agent.mcp.protocol.McpRemoteTool;
import io.haifa.agent.tool.api.ToolInvocationException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class McpToolDiscoveryService {
    private final McpConnectionManager connections;
    private final McpToolDefinitionMapper mapper;
    private final CredentialBroker credentials;
    private final Clock clock;
    private final int maxPages;
    private final int maxTools;
    private final int maxTotalSchemaChars;
    private final Duration deadline;

    public McpToolDiscoveryService(
            McpConnectionManager connections,
            McpToolDefinitionMapper mapper,
            CredentialBroker credentials,
            Clock clock,
            int maxPages,
            int maxTools,
            int maxTotalSchemaChars,
            Duration deadline) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxPages < 1
                || maxPages > 1000
                || maxTools < 1
                || maxTools > 10_000
                || maxTotalSchemaChars < 1024
                || maxTotalSchemaChars > 64 * 1024 * 1024) {
            throw new IllegalArgumentException("MCP discovery budget is out of range");
        }
        this.maxPages = maxPages;
        this.maxTools = maxTools;
        this.maxTotalSchemaChars = maxTotalSchemaChars;
        this.deadline = Objects.requireNonNull(deadline, "deadline");
    }

    public List<McpToolImportCandidate> discover(McpServerId serverId, McpDiscoveryContext context) {
        var server = connections.definition(serverId);
        Instant started = clock.instant();
        Instant expires = started.plus(deadline);
        List<CredentialLease> initializeLeases =
                issue(serverId, context, CredentialOperation.MCP_CONNECTION_INITIALIZE, started, expires);
        McpConnection connection;
        try {
            connection = connections.acquire(serverId, context.tenant(), context.principal(), initializeLeases);
            if (server.transport() instanceof StdioDefinition) connections.invalidate(connection);
        } finally {
            closeReverse(initializeLeases);
        }
        List<CredentialLease> discoveryLeases =
                issue(serverId, context, CredentialOperation.MCP_DISCOVERY, started, expires);
        try {
            if (server.transport() instanceof StdioDefinition) {
                connection = connections.acquire(serverId, context.tenant(), context.principal(), discoveryLeases);
            }
            McpConnection activeConnection = connection;
            boolean sessionRecovered = false;
            List<McpRemoteTool> discovered = new ArrayList<>();
            HashSet<String> names = new HashSet<>();
            String cursor = null;
            int schemaChars = 0;
            for (int pageNumber = 0; ; pageNumber++) {
                if (pageNumber >= maxPages || !clock.instant().isBefore(expires)) {
                    throw new IllegalStateException("MCP discovery exceeded its page or time budget");
                }
                io.haifa.agent.mcp.protocol.McpListToolsPage page;
                try {
                    page = activeConnection.client().listTools(cursor, discoveryLeases);
                } catch (ToolInvocationException exception) {
                    if (sessionRecovered || !"MCP_SESSION_INVALID".equals(exception.failureCode())) throw exception;
                    connections.invalidate(activeConnection);
                    activeConnection =
                            connections.acquire(serverId, context.tenant(), context.principal(), discoveryLeases);
                    sessionRecovered = true;
                    page = activeConnection.client().listTools(cursor, discoveryLeases);
                }
                for (McpRemoteTool tool : page.tools()) {
                    if (!names.add(tool.name())) throw new IllegalStateException("duplicate MCP remote tool name");
                    discovered.add(tool);
                    schemaChars = Math.addExact(
                            schemaChars,
                            io.haifa.agent.mcp.protocol.McpCanonicalizer.canonicalize(tool.inputSchema())
                                            .length()
                                    + io.haifa.agent.mcp.protocol.McpCanonicalizer.canonicalize(tool.outputSchema())
                                            .length());
                    if (discovered.size() > maxTools || schemaChars > maxTotalSchemaChars) {
                        throw new IllegalStateException("MCP discovery exceeded its tool or schema budget");
                    }
                }
                Optional<String> next = page.nextCursor();
                if (next.isEmpty()) break;
                cursor = next.orElseThrow();
            }
            McpConnection mappedConnection = activeConnection;
            return discovered.stream()
                    .sorted(Comparator.comparing(McpRemoteTool::name))
                    .map(tool ->
                            mapper.map(server, mappedConnection.serverSnapshot().negotiatedProtocolVersion(), tool))
                    .toList();
        } finally {
            if (server.transport() instanceof StdioDefinition) connections.invalidate(connection);
            closeReverse(discoveryLeases);
        }
    }

    private List<CredentialLease> issue(
            McpServerId serverId,
            McpDiscoveryContext context,
            CredentialOperation operation,
            Instant requestedAt,
            Instant expiresAt) {
        var server = connections.definition(serverId);
        List<CredentialLease> leases = new ArrayList<>();
        try {
            for (var injection : server.discoveryCredentials()) {
                leases.add(credentials.issue(new CredentialOperationRequest(
                        operation,
                        context.tenant(),
                        context.principal(),
                        server.bindingReference(),
                        injection.requirement(),
                        context.credentialScopeChain(),
                        Optional.empty(),
                        requestedAt,
                        expiresAt)));
            }
            return leases;
        } catch (RuntimeException exception) {
            closeReverse(leases);
            throw exception;
        }
    }

    private static void closeReverse(List<CredentialLease> leases) {
        for (int index = leases.size() - 1; index >= 0; index--)
            leases.get(index).close();
    }
}

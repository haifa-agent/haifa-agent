package io.haifa.agent.personalassistant.server.configuration.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.personalassistant.application.mcp.PersonalMcpConfiguration;
import io.haifa.agent.personalassistant.server.configuration.product.PersonalAssistantProperties;
import java.net.URI;
import java.util.Optional;

/** Owns the optional embedded MCP fixture or points at an explicitly configured loopback MCP process. */
public final class PersonalMcpRuntime implements AutoCloseable {
    private final String mode;
    private final PersonalMcpConfiguration configuration;
    private final Optional<LocalPersonalMcpServer> embedded;

    public PersonalMcpRuntime(PersonalAssistantProperties.Mcp properties, ObjectMapper mapper) {
        mode = properties.mode();
        if ("embedded-echo".equals(mode)) {
            LocalPersonalMcpServer server = new LocalPersonalMcpServer(properties.address(), properties.port(), mapper);
            embedded = Optional.of(server);
            configuration = new PersonalMcpConfiguration(
                    server.endpoint(),
                    properties.serverId(),
                    properties.displayName(),
                    properties.allowedTools(),
                    properties.aliasNamespace());
        } else {
            embedded = Optional.empty();
            configuration = new PersonalMcpConfiguration(
                    properties.endpoint(),
                    properties.serverId(),
                    properties.displayName(),
                    properties.allowedTools(),
                    properties.aliasNamespace());
        }
    }

    public PersonalMcpConfiguration configuration() {
        return configuration;
    }

    public URI endpoint() {
        return configuration.endpoint();
    }

    public String mode() {
        return mode;
    }

    @Override
    public void close() {
        embedded.ifPresent(LocalPersonalMcpServer::close);
    }
}

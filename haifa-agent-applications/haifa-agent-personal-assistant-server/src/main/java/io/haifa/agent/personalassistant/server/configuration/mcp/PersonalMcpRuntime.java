package io.haifa.agent.personalassistant.server.configuration.mcp;

import io.haifa.agent.personalassistant.application.mcp.PersonalMcpConfiguration;
import io.haifa.agent.personalassistant.server.configuration.product.PersonalAssistantProperties;
import java.net.URI;

/** Maps the packaged product MCP configuration onto the pure-Java MCP configuration or an explicit disabled state. */
public final class PersonalMcpRuntime {
    public static final String DISABLED = "disabled";

    private final String mode;
    private final PersonalMcpConfiguration configuration;

    public PersonalMcpRuntime(PersonalAssistantProperties.Mcp properties) {
        mode = properties.mode();
        configuration = DISABLED.equals(mode)
                ? null
                : new PersonalMcpConfiguration(
                        properties.endpoint(),
                        properties.serverId(),
                        properties.displayName(),
                        properties.allowedTools(),
                        properties.aliasNamespace(),
                        properties.required());
    }

    public String mode() {
        return mode;
    }

    /** Configured loopback endpoint, or {@code null} when MCP is disabled. */
    public URI endpoint() {
        return configuration == null ? null : configuration.endpoint();
    }

    /** Configured MCP server, or {@code null} when MCP is disabled. */
    public PersonalMcpConfiguration configuration() {
        return configuration;
    }
}

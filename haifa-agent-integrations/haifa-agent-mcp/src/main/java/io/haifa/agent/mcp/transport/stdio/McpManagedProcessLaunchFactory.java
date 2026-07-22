package io.haifa.agent.mcp.transport.stdio;

import io.haifa.agent.credential.api.CredentialLease;
import io.haifa.agent.mcp.client.McpConnectionIdentity;
import io.haifa.agent.mcp.config.McpServerDefinition;
import java.util.List;

@FunctionalInterface
public interface McpManagedProcessLaunchFactory {
    McpManagedProcessLaunch prepare(
            McpServerDefinition server, McpConnectionIdentity identity, List<CredentialLease> credentials);
}

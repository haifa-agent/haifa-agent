package io.haifa.agent.mcp.transport.stdio;

import io.haifa.agent.credential.api.CredentialLease;
import io.haifa.agent.execution.api.ExecutionCommandMode;
import io.haifa.agent.mcp.client.McpConnectionIdentity;
import io.haifa.agent.mcp.config.McpServerDefinition;
import io.haifa.agent.mcp.config.StdioDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class DefaultMcpManagedProcessLaunchFactory implements McpManagedProcessLaunchFactory {
    private final McpStdioEnvironmentRegistry environments;
    private final McpManagedProcessRequestTemplate requestTemplate;

    public DefaultMcpManagedProcessLaunchFactory(
            McpStdioEnvironmentRegistry environments, McpManagedProcessRequestTemplate requestTemplate) {
        this.environments = Objects.requireNonNull(environments, "environments");
        this.requestTemplate = Objects.requireNonNull(requestTemplate, "requestTemplate");
    }

    @Override
    public McpManagedProcessLaunch prepare(
            McpServerDefinition server, McpConnectionIdentity identity, List<CredentialLease> credentials) {
        if (!(server.transport() instanceof StdioDefinition stdio)) {
            throw new IllegalArgumentException("managed process factory only accepts stdio definitions");
        }
        var binding = environments.bind(server.discoveryCredentials(), credentials, stdio.environmentNameAllowlist());
        try {
            var request = requestTemplate.create(server, identity, binding.reference());
            var execution = request.execution();
            var expected = new ArrayList<String>();
            expected.add(stdio.executable());
            expected.addAll(stdio.fixedArguments());
            if (execution.command().mode() != ExecutionCommandMode.DIRECT
                    || !execution.command().argv().equals(expected)
                    || !execution.environmentRef().equals(binding.reference())) {
                throw new SecurityException("MCP stdio execution request diverged from the approved definition");
            }
            return new McpManagedProcessLaunch(request, binding);
        } catch (RuntimeException exception) {
            binding.close();
            throw exception;
        }
    }
}

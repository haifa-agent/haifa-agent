package io.haifa.agent.mcp.client;

import io.haifa.agent.credential.api.CredentialLease;
import io.haifa.agent.tool.api.ToolInvocationObserver;
import java.util.List;
import java.util.Map;

interface ModernMcpTransport extends AutoCloseable {
    Map<String, Object> request(
            String method,
            Map<String, Object> parameters,
            Map<String, String> envelopeHeaders,
            List<CredentialLease> credentials,
            ToolInvocationObserver observer);

    @Override
    void close();
}

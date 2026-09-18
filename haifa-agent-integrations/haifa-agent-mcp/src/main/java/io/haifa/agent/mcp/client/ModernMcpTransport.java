package io.haifa.agent.mcp.client;

import io.haifa.agent.tool.api.ToolInvocationObserver;
import java.util.Map;

interface ModernMcpTransport extends AutoCloseable {
    Map<String, Object> request(
            String method,
            Map<String, Object> parameters,
            Map<String, String> envelopeHeaders,
            Map<String, String> credentials,
            ToolInvocationObserver observer);

    @Override
    void close();
}

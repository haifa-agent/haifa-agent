package io.haifa.agent.tool.api;

import io.haifa.agent.core.tool.ToolResult;

public interface ToolProvider {
    ToolProviderId id();

    ToolResult invoke(ToolInvocationRequest request);

    default ToolReconciliation reconcile(ToolReconciliationRequest request) {
        return ToolReconciliation.unsupported();
    }
}

package io.haifa.agent.tool.api;

import io.haifa.agent.core.tool.ToolResult;

@FunctionalInterface
public interface ToolInvoker {
    ToolResult invoke(ToolInvocationRequest request);

    default ToolReconciliation reconcile(ToolReconciliationRequest request) {
        return ToolReconciliation.unsupported();
    }

    default void validateBinding(FrozenToolBinding binding) {
        // Stateless invokers have no additional availability state to validate.
    }
}

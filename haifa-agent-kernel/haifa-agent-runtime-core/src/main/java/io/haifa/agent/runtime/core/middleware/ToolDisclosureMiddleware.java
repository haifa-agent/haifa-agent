package io.haifa.agent.runtime.core.middleware;

import java.util.Set;

public final class ToolDisclosureMiddleware implements AgentRuntimeMiddleware {
    private final Set<String> tools;

    public ToolDisclosureMiddleware(Set<String> tools) {
        this.tools = Set.copyOf(tools);
    }

    @Override
    public RuntimePhase phase() {
        return RuntimePhase.BEFORE_CONTEXT_BUILD;
    }

    @Override
    public RuntimeMiddlewareOrder order() {
        return new RuntimeMiddlewareOrder(600);
    }

    @Override
    public void apply(RuntimeMiddlewareContext context) {
        context.put("tools.available", tools);
    }
}

package io.haifa.agent.runtime.core.middleware;

public interface AgentRuntimeMiddleware {
    RuntimePhase phase();

    RuntimeMiddlewareOrder order();

    void apply(RuntimeMiddlewareContext context);
}

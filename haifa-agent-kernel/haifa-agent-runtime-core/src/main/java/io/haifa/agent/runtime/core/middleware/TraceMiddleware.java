package io.haifa.agent.runtime.core.middleware;

public final class TraceMiddleware implements AgentRuntimeMiddleware {
    @Override
    public RuntimePhase phase() {
        return RuntimePhase.BEFORE_MODEL_CALL;
    }

    @Override
    public RuntimeMiddlewareOrder order() {
        return new RuntimeMiddlewareOrder(100);
    }

    @Override
    public void apply(RuntimeMiddlewareContext context) {
        context.put("trace.runId", context.run().id().value());
    }
}

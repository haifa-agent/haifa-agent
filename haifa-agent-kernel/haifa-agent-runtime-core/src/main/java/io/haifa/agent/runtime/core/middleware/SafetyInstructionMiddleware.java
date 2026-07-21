package io.haifa.agent.runtime.core.middleware;

public final class SafetyInstructionMiddleware implements AgentRuntimeMiddleware {
    @Override
    public RuntimePhase phase() {
        return RuntimePhase.BEFORE_CONTEXT_BUILD;
    }

    @Override
    public RuntimeMiddlewareOrder order() {
        return new RuntimeMiddlewareOrder(100);
    }

    @Override
    public void apply(RuntimeMiddlewareContext context) {
        context.put(
                "safety.instruction", "Use only disclosed capabilities; request approval for guarded side effects.");
    }
}

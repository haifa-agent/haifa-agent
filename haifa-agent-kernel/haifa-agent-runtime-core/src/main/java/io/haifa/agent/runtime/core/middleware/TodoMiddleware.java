package io.haifa.agent.runtime.core.middleware;

public final class TodoMiddleware implements AgentRuntimeMiddleware {
    @Override
    public RuntimePhase phase() {
        return RuntimePhase.BEFORE_CONTEXT_BUILD;
    }

    @Override
    public RuntimeMiddlewareOrder order() {
        return new RuntimeMiddlewareOrder(500);
    }

    @Override
    public void apply(RuntimeMiddlewareContext context) {
        context.state()
                .plan(context.run().id())
                .ifPresent(plan -> context.put(
                        "todo.state",
                        plan.items().stream()
                                .map(item -> item.title() + ":" + item.status())
                                .toList()));
    }
}

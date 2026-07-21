package io.haifa.agent.runtime.core.middleware;

import io.haifa.agent.context.item.ContextItemType;
import io.haifa.agent.context.item.ContextPriority;
import io.haifa.agent.context.item.ContextRetention;
import io.haifa.agent.context.item.ContextRole;
import java.util.Set;

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
        context.state().plan(context.run().id()).ifPresent(plan -> {
            String state = plan.items().stream()
                    .map(item -> item.title() + ":" + item.status())
                    .collect(java.util.stream.Collectors.joining("\n"));
            if (!state.isBlank()) {
                context.addContextItem(RuntimeContextItems.text(
                        "todo-" + plan.id().value(),
                        ContextItemType.RUNTIME_STATE,
                        ContextRole.SYSTEM,
                        state,
                        ContextPriority.HIGH,
                        ContextRetention.KEEP_IF_RELEVANT,
                        "plan",
                        plan.id().value(),
                        Long.toString(plan.revision()),
                        Set.of("internal", "plan")));
            }
        });
    }
}

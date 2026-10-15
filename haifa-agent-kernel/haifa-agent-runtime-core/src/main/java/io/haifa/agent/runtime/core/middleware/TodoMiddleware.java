package io.haifa.agent.runtime.core.middleware;

import io.haifa.agent.context.item.ContextItemType;
import io.haifa.agent.context.item.ContextPriority;
import io.haifa.agent.context.item.ContextRetention;
import io.haifa.agent.context.item.ContextRole;
import io.haifa.agent.context.prompt.PromptComponent;
import io.haifa.agent.context.prompt.PromptComponentId;
import io.haifa.agent.context.prompt.PromptLayer;
import io.haifa.agent.context.prompt.PromptRole;
import java.util.Objects;
import java.util.Set;

public final class TodoMiddleware implements AgentRuntimeMiddleware {
    private final String systemPrompt;

    public TodoMiddleware() {
        this("");
    }

    public TodoMiddleware(String systemPrompt) {
        this.systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
    }

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
        if (!systemPrompt.isBlank()) {
            context.addPrompt(new PromptComponent(
                    new PromptComponentId("runtime-todo"),
                    "1.0",
                    PromptLayer.TOOL_PROTOCOL,
                    PromptRole.SYSTEM,
                    systemPrompt,
                    false,
                    Set.of("todo", "plan")));
        }
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

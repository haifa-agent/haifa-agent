package io.haifa.agent.runtime.core.middleware;

import io.haifa.agent.context.prompt.PromptComponent;
import io.haifa.agent.context.prompt.PromptComponentId;
import io.haifa.agent.context.prompt.PromptLayer;
import io.haifa.agent.context.prompt.PromptRole;
import java.util.Set;

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
        context.addPrompt(new PromptComponent(
                new PromptComponentId("runtime-safety"),
                "1.0",
                PromptLayer.SYSTEM_SAFETY,
                PromptRole.SYSTEM,
                "Use only disclosed capabilities; request approval for guarded side effects.",
                false,
                Set.of("safety", "internal")));
    }
}

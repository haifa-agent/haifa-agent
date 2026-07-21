package io.haifa.agent.runtime.core.loop;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.runtime.core.middleware.AgentRuntimeMiddlewareChain;
import io.haifa.agent.runtime.core.middleware.RuntimeMiddlewareContext;
import io.haifa.agent.runtime.core.middleware.RuntimePhase;
import io.haifa.agent.runtime.core.model.ModelRequest;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import java.util.Objects;

public final class DefaultContextBuilder implements ContextBuilder {
    private final RuntimeStateRepository state;
    private final AgentRuntimeMiddlewareChain middleware;

    public DefaultContextBuilder(RuntimeStateRepository state, AgentRuntimeMiddlewareChain middleware) {
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.middleware = Objects.requireNonNull(middleware, "middleware must not be null");
    }

    @Override
    public ContextBuildResult build(AgentRun run, AgentLoopContext loopContext) {
        RuntimeMiddlewareContext context = new RuntimeMiddlewareContext(run, state);
        middleware.apply(RuntimePhase.BEFORE_CONTEXT_BUILD, context);
        if (!loopContext.convergenceReasons().isEmpty()) {
            context.put("runtime.convergenceRequired", loopContext.convergenceReasons());
        }
        middleware.apply(RuntimePhase.AFTER_CONTEXT_BUILD, context);
        return new ContextBuildResult(
                new ModelRequest(run.id(), loopContext.iteration(), state.messages(run.id()), context.attributes()),
                context);
    }
}

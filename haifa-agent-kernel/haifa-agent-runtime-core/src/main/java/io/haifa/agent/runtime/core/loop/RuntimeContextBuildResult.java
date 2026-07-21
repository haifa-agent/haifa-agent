package io.haifa.agent.runtime.core.loop;

import io.haifa.agent.context.api.ContextBuildResult;
import io.haifa.agent.runtime.core.middleware.RuntimeMiddlewareContext;
import java.util.Objects;

public record RuntimeContextBuildResult(ContextBuildResult context, RuntimeMiddlewareContext middlewareContext) {
    public RuntimeContextBuildResult {
        context = Objects.requireNonNull(context, "context must not be null");
        middlewareContext = Objects.requireNonNull(middlewareContext, "middlewareContext must not be null");
    }
}

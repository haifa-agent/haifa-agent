package io.haifa.agent.runtime.core.loop;

import io.haifa.agent.runtime.core.middleware.RuntimeMiddlewareContext;
import io.haifa.agent.runtime.core.model.ModelRequest;
import java.util.Objects;

public record ContextBuildResult(ModelRequest request, RuntimeMiddlewareContext middlewareContext) {
    public ContextBuildResult {
        request = Objects.requireNonNull(request, "request must not be null");
        middlewareContext = Objects.requireNonNull(middlewareContext, "middlewareContext must not be null");
    }
}

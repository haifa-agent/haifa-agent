package io.haifa.agent.runtime.core.loop;

import io.haifa.agent.context.api.ContextBuildResult;
import io.haifa.agent.runtime.core.middleware.RuntimeMiddlewareContext;
import java.util.Objects;

public record RuntimeContextBuildResult(
        ContextBuildResult context,
        RuntimeMiddlewareContext middlewareContext,
        SessionMessageSource.Selection sessionSelection,
        String windowIdentity,
        java.util.Optional<io.haifa.agent.runtime.core.model.PreparedModelInput> preparedInput,
        java.util.Optional<io.haifa.agent.runtime.core.compaction.CompactionTriggerDecision> compactionDecision) {
    public RuntimeContextBuildResult(
            ContextBuildResult context,
            RuntimeMiddlewareContext middlewareContext,
            SessionMessageSource.Selection sessionSelection,
            String windowIdentity) {
        this(
                context,
                middlewareContext,
                sessionSelection,
                windowIdentity,
                java.util.Optional.empty(),
                java.util.Optional.empty());
    }

    public RuntimeContextBuildResult {
        preparedInput = Objects.requireNonNull(preparedInput);
        compactionDecision = Objects.requireNonNull(compactionDecision);
        context = Objects.requireNonNull(context, "context must not be null");
        middlewareContext = Objects.requireNonNull(middlewareContext, "middlewareContext must not be null");
        sessionSelection = Objects.requireNonNull(sessionSelection, "sessionSelection must not be null");
        windowIdentity = Objects.requireNonNull(windowIdentity, "windowIdentity must not be null");
    }
}

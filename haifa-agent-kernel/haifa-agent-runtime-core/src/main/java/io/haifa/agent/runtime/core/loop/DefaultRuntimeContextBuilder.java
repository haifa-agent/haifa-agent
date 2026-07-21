package io.haifa.agent.runtime.core.loop;

import io.haifa.agent.context.api.AgentContextBuilder;
import io.haifa.agent.context.api.ContextBuildException;
import io.haifa.agent.context.api.ContextBuildFailure;
import io.haifa.agent.context.api.ContextBuildRequest;
import io.haifa.agent.context.item.ContextItem;
import io.haifa.agent.context.item.MessageGroupContextContent;
import io.haifa.agent.context.prompt.PromptComponent;
import io.haifa.agent.context.prompt.PromptComponentId;
import io.haifa.agent.context.prompt.PromptLayer;
import io.haifa.agent.context.prompt.PromptRole;
import io.haifa.agent.core.content.ArtifactRefPart;
import io.haifa.agent.core.content.AssetRefPart;
import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.runtime.core.middleware.AgentRuntimeMiddlewareChain;
import io.haifa.agent.runtime.core.middleware.RuntimeMiddlewareContext;
import io.haifa.agent.runtime.core.middleware.RuntimePhase;
import io.haifa.agent.runtime.core.model.FrozenModelBinding;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Builds trusted Context IR from frozen run state and persisted facts. */
public final class DefaultRuntimeContextBuilder implements RuntimeContextBuilder {
    private final RuntimeStateRepository state;
    private final AgentRuntimeMiddlewareChain middleware;
    private final AgentContextBuilder contexts;
    private final SessionMessageSource sessionMessages;
    private final MemoryContextSource memorySource;

    public DefaultRuntimeContextBuilder(
            RuntimeStateRepository state,
            AgentRuntimeMiddlewareChain middleware,
            AgentContextBuilder contexts,
            SessionMessageSource sessionMessages,
            MemoryContextSource memorySource) {
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.middleware = Objects.requireNonNull(middleware, "middleware must not be null");
        this.contexts = Objects.requireNonNull(contexts, "contexts must not be null");
        this.sessionMessages = Objects.requireNonNull(sessionMessages, "sessionMessages must not be null");
        this.memorySource = Objects.requireNonNull(memorySource, "memorySource must not be null");
    }

    @Override
    public RuntimeContextBuildResult build(AgentRun run, AgentLoopContext loopContext, FrozenModelBinding model) {
        RuntimeMiddlewareContext middlewareContext = new RuntimeMiddlewareContext(run, state);
        middleware.apply(RuntimePhase.BEFORE_CONTEXT_BUILD, middlewareContext);
        if (!loopContext.convergenceReasons().isEmpty()) {
            middlewareContext.addPrompt(new PromptComponent(
                    new PromptComponentId("runtime-convergence"),
                    "1.0",
                    PromptLayer.RUNTIME_CONTROL,
                    PromptRole.RUNTIME,
                    "Completion requirements: " + String.join(", ", loopContext.convergenceReasons()),
                    false,
                    java.util.Set.of("internal")));
        }
        middleware.apply(RuntimePhase.AFTER_CONTEXT_BUILD, middlewareContext);

        SessionMessageSource.Selection selection =
                sessionMessages.select(run, loopContext.forcedContextRebuildAttempts());
        List<ContextItem> items = new ArrayList<>(middlewareContext.contextItems());
        selection.items().stream()
                .filter(item -> item.content() instanceof MessageGroupContextContent)
                .map(item -> (MessageGroupContextContent) item.content())
                .flatMap(group -> group.messages().stream())
                .forEach(this::validateContents);
        items.addAll(selection.items());
        items.addAll(memorySource.select(run, model));
        int divisor = loopContext.forcedContextRebuildAttempts() > 0 ? 10 : 20;
        int safetyMargin =
                Math.min(16_384, Math.max(256, model.configuration().model().contextWindow() / divisor));
        var request = new ContextBuildRequest(
                run.id(),
                run.sessionId(),
                run.tenant(),
                run.principal(),
                loopContext.iteration(),
                model.configuration().model(),
                run.budget(),
                run.usage(),
                middlewareContext.prompts(),
                items,
                model.tools(),
                model.configuration().model().maxOutputTokens(),
                safetyMargin,
                selection.policyVersion(),
                selection.compressorVersion(),
                loopContext.forcedContextRebuildAttempts());
        return new RuntimeContextBuildResult(contexts.build(request), middlewareContext);
    }

    private void validateContents(AgentMessage message) {
        for (ContentPart part : message.contents()) {
            if (part instanceof AssetRefPart || part instanceof ArtifactRefPart) {
                throw new ContextBuildException(
                        ContextBuildFailure.UNSUPPORTED_CONTEXT_CONTENT,
                        "asset and artifact references require an authorized derived text context source");
            }
        }
    }
}

package io.haifa.agent.runtime.core.loop;

import io.haifa.agent.context.api.AgentContextBuilder;
import io.haifa.agent.context.api.ContextBuildException;
import io.haifa.agent.context.api.ContextBuildFailure;
import io.haifa.agent.context.api.ContextBuildRequest;
import io.haifa.agent.context.budget.HeuristicTokenEstimator;
import io.haifa.agent.context.item.ContextItem;
import io.haifa.agent.context.item.ContextItemId;
import io.haifa.agent.context.item.ContextItemType;
import io.haifa.agent.context.item.ContextPriority;
import io.haifa.agent.context.item.ContextProvenance;
import io.haifa.agent.context.item.ContextRetention;
import io.haifa.agent.context.item.ContextSecurity;
import io.haifa.agent.context.item.MessageContextContent;
import io.haifa.agent.context.prompt.PromptComponent;
import io.haifa.agent.context.prompt.PromptComponentId;
import io.haifa.agent.context.prompt.PromptLayer;
import io.haifa.agent.context.prompt.PromptRole;
import io.haifa.agent.core.content.ArtifactRefPart;
import io.haifa.agent.core.content.AssetRefPart;
import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.content.ToolCallPart;
import io.haifa.agent.core.content.ToolResultPart;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.runtime.core.middleware.AgentRuntimeMiddlewareChain;
import io.haifa.agent.runtime.core.middleware.RuntimeMiddlewareContext;
import io.haifa.agent.runtime.core.middleware.RuntimePhase;
import io.haifa.agent.runtime.core.model.FrozenModelBinding;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds trusted Context IR from frozen run state and persisted facts. */
public final class DefaultRuntimeContextBuilder implements RuntimeContextBuilder {
    private final RuntimeStateRepository state;
    private final AgentRuntimeMiddlewareChain middleware;
    private final AgentContextBuilder contexts;

    public DefaultRuntimeContextBuilder(
            RuntimeStateRepository state, AgentRuntimeMiddlewareChain middleware, AgentContextBuilder contexts) {
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.middleware = Objects.requireNonNull(middleware, "middleware must not be null");
        this.contexts = Objects.requireNonNull(contexts, "contexts must not be null");
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

        List<AgentMessage> messages = state.messages(run.id()).stream()
                .filter(message -> message.status() == MessageStatus.COMPLETED)
                .toList();
        List<ContextItem> items = new ArrayList<>(middlewareContext.contextItems());
        for (int index = 0; index < messages.size(); index++) {
            AgentMessage message = messages.get(index);
            validateContents(message);
            boolean current = index == messages.size() - 1;
            String hash = hash(
                    message.id().value() + "|" + message.role() + "|" + message.sequence() + "|" + message.contents());
            items.add(new ContextItem(
                    new ContextItemId("message-" + message.id().value()),
                    ContextItemType.MESSAGE,
                    new MessageContextContent(message),
                    estimate(message),
                    current ? ContextPriority.CRITICAL : ContextPriority.NORMAL,
                    current ? ContextRetention.MUST_KEEP : ContextRetention.COMPRESSIBLE,
                    new ContextSecurity(
                            java.util.Set.of(message.visibility().name().toLowerCase()), true),
                    new ContextProvenance("message", message.id().value(), Long.toString(message.sequence()), hash),
                    Map.of("role", message.role().name(), "sequence", Long.toString(message.sequence()))));
        }
        int safetyMargin =
                Math.min(8_192, Math.max(256, model.configuration().model().contextWindow() / 20));
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
                safetyMargin);
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

    private int estimate(AgentMessage message) {
        long characters = 0;
        for (ContentPart part : message.contents()) {
            if (part instanceof TextPart text) characters += text.text().length();
            else if (part instanceof ToolCallPart call)
                characters += call.toolName().length() + 32L;
            else if (part instanceof ToolResultPart result)
                characters += result.summary().length() + 16L;
        }
        return Math.max(
                1, HeuristicTokenEstimator.tokens("x".repeat(Math.toIntExact(Math.min(1_000_000, characters)))) + 4);
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}

package io.haifa.agent.runtime.core.middleware;

import io.haifa.agent.context.item.ContextItemType;
import io.haifa.agent.context.item.ContextPriority;
import io.haifa.agent.context.item.ContextRetention;
import io.haifa.agent.context.item.ContextRole;
import io.haifa.agent.context.prompt.PromptComponent;
import io.haifa.agent.context.prompt.PromptComponentId;
import io.haifa.agent.context.prompt.PromptLayer;
import io.haifa.agent.context.prompt.PromptRole;
import java.util.Set;

public final class RunMetadataMiddleware implements AgentRuntimeMiddleware {
    @Override
    public RuntimePhase phase() {
        return RuntimePhase.BEFORE_CONTEXT_BUILD;
    }

    @Override
    public RuntimeMiddlewareOrder order() {
        return new RuntimeMiddlewareOrder(200);
    }

    @Override
    public void apply(RuntimeMiddlewareContext context) {
        var configuration = context.state()
                .configuration(context.run().configurationSnapshot())
                .orElseThrow(() -> new IllegalStateException("run configuration snapshot is unavailable"));
        context.put("run.id", context.run().id().value());
        context.put("run.objective", context.run().objective());
        context.addContextItem(RuntimeContextItems.text(
                "run-objective-" + context.run().id().value(),
                ContextItemType.RUNTIME_STATE,
                ContextRole.USER,
                context.run().objective(),
                ContextPriority.CRITICAL,
                ContextRetention.MUST_KEEP,
                "run",
                context.run().id().value(),
                Long.toString(context.run().version()),
                Set.of("run-objective")));
        context.put("product.profile", configuration.profileId() + "@" + configuration.profileVersion());
        context.addPrompt(new PromptComponent(
                new PromptComponentId(
                        "agent-definition-" + configuration.definitionId().value()),
                configuration.definitionVersion().toString(),
                PromptLayer.AGENT_DEFINITION,
                PromptRole.DEVELOPER,
                configuration.agentInstruction(),
                false,
                Set.of("agent-definition")));
        context.run().project().ifPresent(project -> context.put("project.id", project.projectId()));
    }
}

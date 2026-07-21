package io.haifa.agent.runtime.core.middleware;

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
        context.put("product.profile", configuration.profileId() + "@" + configuration.profileVersion());
        context.put("agent.instruction", configuration.agentInstruction());
        context.run().project().ifPresent(project -> context.put("project.id", project.projectId()));
    }
}

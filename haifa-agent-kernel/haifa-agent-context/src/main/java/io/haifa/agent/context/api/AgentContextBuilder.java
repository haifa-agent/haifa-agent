package io.haifa.agent.context.api;

@FunctionalInterface
public interface AgentContextBuilder {
    ContextBuildResult build(ContextBuildRequest request);
}

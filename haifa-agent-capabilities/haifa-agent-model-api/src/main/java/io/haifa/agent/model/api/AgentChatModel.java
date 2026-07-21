package io.haifa.agent.model.api;

/** Synchronous provider-neutral model invocation boundary. */
@FunctionalInterface
public interface AgentChatModel {
    AgentChatResponse invoke(AgentChatRequest request);
}

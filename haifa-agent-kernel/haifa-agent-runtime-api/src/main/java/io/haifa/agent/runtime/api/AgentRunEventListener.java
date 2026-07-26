package io.haifa.agent.runtime.api;

@FunctionalInterface
public interface AgentRunEventListener {
    void onEvent(AgentRunEvent event);
}

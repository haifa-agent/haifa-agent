package io.haifa.agent.runtime.api;

/** Receives safe incremental output. Listener failures are isolated from the AgentLoop. */
@FunctionalInterface
public interface AgentRunOutputListener {
    void onOutput(AgentRunOutputEvent event);
}

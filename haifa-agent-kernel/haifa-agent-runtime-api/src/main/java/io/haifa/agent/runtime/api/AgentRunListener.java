package io.haifa.agent.runtime.api;

/** Receives immutable run snapshots after lifecycle changes. */
@FunctionalInterface
public interface AgentRunListener {

    void onRunChanged(AgentRunResult result);
}

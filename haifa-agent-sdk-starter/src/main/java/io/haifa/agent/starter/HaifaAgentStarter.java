package io.haifa.agent.starter;

import io.haifa.agent.sdk.api.HaifaAgent;

/** Entry point for safe-default, process-local Haifa Agent SDK development. */
public final class HaifaAgentStarter {
    private HaifaAgentStarter() {}

    /**
     * Creates a ready-to-use Agent with the Starter defaults.
     *
     * <p>The default model is DeepSeek V4 Flash. The Agent reads {@code DEEPSEEK_API_KEY}, disables
     * Thinking, and keeps Runtime and Conversation state only for the life of the process.
     *
     * @return a ready-to-use process-local Agent
     */
    public static HaifaAgent create() {
        return builder().build();
    }

    /**
     * Creates a customizable builder whose default model is DeepSeek V4 Flash.
     *
     * @return a new independent Starter builder
     */
    public static HaifaAgentStarterBuilder builder() {
        return new HaifaAgentStarterBuilder();
    }
}

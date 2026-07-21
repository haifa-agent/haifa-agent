package io.haifa.agent.context.prompt;

/** Ordered from strongest platform constraint to the most local instruction. */
public enum PromptLayer {
    SYSTEM_SAFETY,
    PLATFORM_POLICY,
    AGENT_DEFINITION,
    RUNTIME_CONTROL,
    TOOL_PROTOCOL
}

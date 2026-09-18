package io.haifa.agent.sdk.diagnostics;

/** Bounded source classification for redacted Prompt diagnostics. */
public enum PromptDiagnosticSource {
    STARTER_INSTRUCTIONS,
    AGENT_INSTRUCTIONS,
    RUNTIME_SAFETY,
    PLATFORM_POLICY,
    TOOL_PROTOCOL,
    SKILL,
    MEMORY,
    SUMMARY,
    RUNTIME_CONTROL,
    SESSION_CONTEXT,
    OTHER_CONTEXT
}

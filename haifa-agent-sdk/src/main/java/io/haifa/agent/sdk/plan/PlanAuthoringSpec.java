package io.haifa.agent.sdk.plan;

import java.util.Objects;

/**
 * Configuration for the optional model-driven plan authoring tool.
 *
 * @param toolName The name of the tool exposed to the model (default: "write_todos").
 * @param toolDescription The description of the tool exposed to the model.
 * @param systemPrompt The system prompt instruction injected into the context.
 */
public record PlanAuthoringSpec(String toolName, String toolDescription, String systemPrompt) {

    public static final String DEFAULT_TOOL_NAME = "write_todos";
    public static final String DEFAULT_TOOL_DESCRIPTION =
            "Create and manage a structured task list for the current session.";
    public static final String DEFAULT_SYSTEM_PROMPT =
            "Track multi-step objectives by updating the todo list in real time using the task planning tool.";

    public PlanAuthoringSpec {
        toolName = requireText(toolName, "toolName");
        toolDescription = requireText(toolDescription, "toolDescription");
        systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
    }

    public static PlanAuthoringSpec defaults() {
        return new PlanAuthoringSpec(DEFAULT_TOOL_NAME, DEFAULT_TOOL_DESCRIPTION, DEFAULT_SYSTEM_PROMPT);
    }

    public static PlanAuthoringSpec of(String toolName, String toolDescription, String systemPrompt) {
        return new PlanAuthoringSpec(toolName, toolDescription, systemPrompt);
    }

    private static String requireText(String value, String field) {
        String checked =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return checked;
    }
}

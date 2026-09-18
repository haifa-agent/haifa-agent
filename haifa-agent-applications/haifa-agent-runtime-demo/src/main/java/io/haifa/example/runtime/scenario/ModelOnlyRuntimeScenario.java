package io.haifa.example.runtime.scenario;

/** Direct Runtime Core assembly with one model call and no Tool, MCP, or Skill capability. */
public final class ModelOnlyRuntimeScenario implements RuntimeScenario {
    @Override
    public String id() {
        return "model-only";
    }

    @Override
    public String defaultObjective() {
        return "Reply with exactly DEEPSEEK_V4_PRO_RUNTIME_OK.";
    }

    @Override
    public String instructions() {
        return """
               Answer the user's objective directly and concisely.
               Do not call tools.
               """;
    }
}

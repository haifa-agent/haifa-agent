package io.haifa.agent.core.step;

import static io.haifa.agent.core.support.DomainValues.requireText;

/** Open persisted step classification, separate from Runtime loop iterations. */
public record AgentStepType(String value) {
    public static final AgentStepType CONTEXT_BUILD = new AgentStepType("context.build");
    public static final AgentStepType MODEL_CALL = new AgentStepType("model.call");
    public static final AgentStepType TOOL_EXECUTION = new AgentStepType("tool.execution");
    public static final AgentStepType DELEGATION = new AgentStepType("agent.delegation");
    public static final AgentStepType INTERACTION = new AgentStepType("human.interaction");
    public static final AgentStepType ARTIFACT_GENERATION = new AgentStepType("artifact.generation");

    public AgentStepType {
        value = requireText(value, "stepType");
    }
}

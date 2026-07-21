package io.haifa.agent.runtime.core.model;

import io.haifa.agent.runtime.core.decision.AgentDecision;

@FunctionalInterface
public interface ModelResponseInterpreter {
    AgentDecision interpret(ModelResponse response);
}

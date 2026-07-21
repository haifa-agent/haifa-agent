package io.haifa.agent.runtime.core.loop;

import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.runtime.core.decision.AgentLoopDirective;
import java.util.Objects;

public record AgentLoopResult(AgentRunStatus status, AgentLoopIteration lastIteration, AgentLoopDirective directive) {
    public AgentLoopResult {
        status = Objects.requireNonNull(status, "status must not be null");
        lastIteration = Objects.requireNonNull(lastIteration, "lastIteration must not be null");
        directive = Objects.requireNonNull(directive, "directive must not be null");
    }
}

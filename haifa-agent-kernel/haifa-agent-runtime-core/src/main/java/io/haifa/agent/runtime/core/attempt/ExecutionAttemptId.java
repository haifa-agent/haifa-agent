package io.haifa.agent.runtime.core.attempt;

import io.haifa.agent.common.id.Identifier;
import io.haifa.agent.common.id.Identifiers;

public record ExecutionAttemptId(String value) implements Identifier {
    public ExecutionAttemptId {
        value = Identifiers.requireValid(value, "executionAttemptId");
    }
}

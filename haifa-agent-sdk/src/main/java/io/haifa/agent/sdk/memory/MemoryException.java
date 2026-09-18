package io.haifa.agent.sdk.memory;

import io.haifa.agent.sdk.api.HaifaAgentException;

public final class MemoryException extends HaifaAgentException {
    public MemoryException(String code, String operation, String correlation) {
        super(code, operation, correlation, code);
    }
}

package io.haifa.agent.execution.api;

public record ManagedProcessSessionId(String value) {
    public ManagedProcessSessionId {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("session id must not be blank");
    }
}

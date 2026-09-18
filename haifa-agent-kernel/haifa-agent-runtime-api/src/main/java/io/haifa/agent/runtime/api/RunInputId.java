package io.haifa.agent.runtime.api;

public record RunInputId(String value) {
    public RunInputId {
        value = InteractionOption.requireText(value, "value", 256);
    }
}

package io.haifa.agent.memory.api;

/** Opaque stable pagination cursor. Callers must persist and replay the value without interpreting it. */
public record MemoryPageCursor(String value) {
    public MemoryPageCursor {
        value = MemoryValues.text(value, "value", 1024);
    }
}

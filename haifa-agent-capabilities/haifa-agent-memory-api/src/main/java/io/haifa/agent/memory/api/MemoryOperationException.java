package io.haifa.agent.memory.api;

/** Stable, content-free Memory application error. */
public final class MemoryOperationException extends RuntimeException {
    private final String code;

    public MemoryOperationException(String code) {
        super(MemoryValues.text(code, "code", 128));
        this.code = code;
    }

    public String code() {
        return code;
    }
}

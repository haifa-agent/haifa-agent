package io.haifa.agent.execution.api;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** Bounded initial standard input supplied to a non-interactive execution. */
public final class ExecutionInput {
    public static final int MAX_BYTES = 64 * 1024;
    private static final ExecutionInput NONE = new ExecutionInput(new byte[0]);

    private final byte[] bytes;

    private ExecutionInput(byte[] bytes) {
        this.bytes = Arrays.copyOf(bytes, bytes.length);
    }

    public static ExecutionInput none() {
        return NONE;
    }

    public static ExecutionInput utf8(String value) {
        return ofBytes(Objects.requireNonNull(value, "value must not be null").getBytes(StandardCharsets.UTF_8));
    }

    public static ExecutionInput ofBytes(byte[] value) {
        byte[] bytes = Arrays.copyOf(Objects.requireNonNull(value, "value must not be null"), value.length);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("execution input exceeds maximum bytes");
        return bytes.length == 0 ? NONE : new ExecutionInput(bytes);
    }

    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }

    public boolean isEmpty() {
        return bytes.length == 0;
    }

    public int size() {
        return bytes.length;
    }

    @Override
    public boolean equals(Object candidate) {
        return candidate instanceof ExecutionInput other && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "ExecutionInput[size=" + bytes.length + "]";
    }
}

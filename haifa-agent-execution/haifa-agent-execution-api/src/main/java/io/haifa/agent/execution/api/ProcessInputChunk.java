package io.haifa.agent.execution.api;

import java.util.Arrays;
import java.util.Objects;

public record ProcessInputChunk(byte[] bytes) {
    public ProcessInputChunk {
        bytes = Arrays.copyOf(Objects.requireNonNull(bytes, "bytes"), bytes.length);
        if (bytes.length == 0 || bytes.length > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("process input chunk size is out of range");
        }
    }

    @Override
    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }
}

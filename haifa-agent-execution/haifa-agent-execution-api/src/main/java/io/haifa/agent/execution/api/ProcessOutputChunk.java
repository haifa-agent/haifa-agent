package io.haifa.agent.execution.api;

import java.util.Arrays;
import java.util.Objects;

public record ProcessOutputChunk(ExecutionOutputChannel channel, byte[] bytes, boolean endOfStream, boolean truncated) {
    public ProcessOutputChunk {
        Objects.requireNonNull(channel, "channel");
        bytes = Arrays.copyOf(Objects.requireNonNull(bytes, "bytes"), bytes.length);
    }

    @Override
    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }
}

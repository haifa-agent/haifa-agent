package io.haifa.agent.model.openai;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Reads UTF-8 SSE lines with a byte bound enforced before constructing a String. */
public final class Utf8SseLineReader implements AutoCloseable {
    private final InputStream input;

    public Utf8SseLineReader(InputStream input) {
        this.input = new BufferedInputStream(Objects.requireNonNull(input, "input must not be null"));
    }

    public Line readLine(int maximumBytes) throws IOException {
        if (maximumBytes < 1) throw new IllegalArgumentException("maximumBytes must be positive");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(maximumBytes, 8 * 1024));
        boolean observed = false;
        boolean terminated = false;
        while (true) {
            int next = input.read();
            if (next < 0) {
                if (!observed) return null;
                break;
            }
            observed = true;
            if (next == '\n') {
                terminated = true;
                break;
            }
            if (bytes.size() >= maximumBytes) throw new LineLimitExceededException(maximumBytes);
            bytes.write(next);
        }
        byte[] raw = bytes.toByteArray();
        int textLength = raw.length > 0 && raw[raw.length - 1] == '\r' ? raw.length - 1 : raw.length;
        return new Line(new String(raw, 0, textLength, StandardCharsets.UTF_8), raw.length + (terminated ? 1 : 0));
    }

    @Override
    public void close() throws IOException {
        input.close();
    }

    public record Line(String value, int transportBytes) {}

    public static final class LineLimitExceededException extends IOException {
        private final int limit;

        private LineLimitExceededException(int limit) {
            super("SSE line exceeds transport byte limit");
            this.limit = limit;
        }

        public int limit() {
            return limit;
        }
    }
}

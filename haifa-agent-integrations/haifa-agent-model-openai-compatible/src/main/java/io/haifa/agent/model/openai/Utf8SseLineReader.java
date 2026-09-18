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
    private int pending = -1;

    public Utf8SseLineReader(InputStream input) {
        this.input = new BufferedInputStream(Objects.requireNonNull(input, "input must not be null"));
    }

    public Line readLine(int maximumBytes) throws IOException {
        if (maximumBytes < 1) throw new IllegalArgumentException("maximumBytes must be positive");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(maximumBytes, 8 * 1024));
        boolean observed = false;
        int terminatorBytes = 0;
        while (true) {
            int next = readByte();
            if (next < 0) {
                if (!observed) return null;
                break;
            }
            observed = true;
            if (next == '\n') {
                terminatorBytes = 1;
                break;
            }
            if (next == '\r') {
                terminatorBytes = 1;
                int following = input.read();
                if (following == '\n') terminatorBytes = 2;
                else pending = following;
                break;
            }
            if (bytes.size() >= maximumBytes) throw new LineLimitExceededException(maximumBytes);
            bytes.write(next);
        }
        byte[] raw = bytes.toByteArray();
        return new Line(new String(raw, StandardCharsets.UTF_8), raw.length + terminatorBytes);
    }

    private int readByte() throws IOException {
        if (pending < 0) return input.read();
        int next = pending;
        pending = -1;
        return next;
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

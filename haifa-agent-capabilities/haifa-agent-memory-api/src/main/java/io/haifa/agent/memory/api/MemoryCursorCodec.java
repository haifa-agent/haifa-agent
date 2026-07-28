package io.haifa.agent.memory.api;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/** Shared deterministic codec used by Memory store implementations; product callers treat cursors as opaque. */
public final class MemoryCursorCodec {
    private static final byte VERSION = 1;

    private MemoryCursorCodec() {}

    public static MemoryPageCursor encode(Instant updatedAt, String logicalId, long sequence) {
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        byte[] id = MemoryValues.text(logicalId, "logicalId", 256).getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(1 + Long.BYTES + Long.BYTES + Integer.BYTES + id.length);
        buffer.put(VERSION)
                .putLong(updatedAt.toEpochMilli())
                .putLong(sequence)
                .putInt(id.length)
                .put(id);
        return new MemoryPageCursor(Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array()));
    }

    public static Position decode(MemoryPageCursor cursor) {
        Objects.requireNonNull(cursor, "cursor must not be null");
        try {
            ByteBuffer buffer = ByteBuffer.wrap(Base64.getUrlDecoder().decode(cursor.value()));
            if (buffer.get() != VERSION) throw invalid();
            Instant updatedAt = Instant.ofEpochMilli(buffer.getLong());
            long sequence = buffer.getLong();
            int length = buffer.getInt();
            if (length < 1 || length > 1_024 || buffer.remaining() != length) throw invalid();
            byte[] id = new byte[length];
            buffer.get(id);
            return new Position(updatedAt, new String(id, StandardCharsets.UTF_8), sequence);
        } catch (IllegalArgumentException | java.nio.BufferUnderflowException exception) {
            throw invalid();
        }
    }

    private static MemoryOperationException invalid() {
        return new MemoryOperationException("MEMORY_CURSOR_INVALID");
    }

    public record Position(Instant updatedAt, String logicalId, long sequence) {
        public Position {
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
            logicalId = MemoryValues.text(logicalId, "logicalId", 256);
        }
    }
}

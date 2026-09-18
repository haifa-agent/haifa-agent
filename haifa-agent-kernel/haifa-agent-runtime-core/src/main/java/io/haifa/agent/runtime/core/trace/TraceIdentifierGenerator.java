package io.haifa.agent.runtime.core.trace;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/** Generates opaque 128-bit Base64URL identifiers for diagnostic trace and stream correlation. */
public final class TraceIdentifierGenerator {
    private static final int RANDOM_BYTES = 16;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final ByteSource bytes;

    public TraceIdentifierGenerator() {
        SecureRandom random = new SecureRandom();
        this.bytes = random::nextBytes;
    }

    TraceIdentifierGenerator(ByteSource bytes) {
        this.bytes = Objects.requireNonNull(bytes, "bytes must not be null");
    }

    public String nextTraceId() {
        return next("tr");
    }

    public String nextStreamId() {
        return next("ts");
    }

    private String next(String prefix) {
        byte[] value = new byte[RANDOM_BYTES];
        bytes.nextBytes(value);
        return prefix + "_" + ENCODER.encodeToString(value);
    }

    @FunctionalInterface
    interface ByteSource {
        void nextBytes(byte[] target);
    }
}

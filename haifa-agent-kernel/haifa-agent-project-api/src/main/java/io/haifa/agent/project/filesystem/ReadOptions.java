package io.haifa.agent.project.filesystem;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public record ReadOptions(long offset, long maxBytes, int maxCharacters, Charset charset, boolean allowTruncation) {
    public ReadOptions(long maxBytes, int maxCharacters, Charset charset, boolean allowTruncation) {
        this(0, maxBytes, maxCharacters, charset, allowTruncation);
    }

    public ReadOptions {
        if (offset < 0) throw new IllegalArgumentException("offset must not be negative");
        if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be positive");
        if (maxCharacters < 1) throw new IllegalArgumentException("maxCharacters must be positive");
        charset = Objects.requireNonNull(charset, "charset must not be null");
    }

    public static ReadOptions defaults() {
        return new ReadOptions(0, 1_048_576, 1_000_000, StandardCharsets.UTF_8, false);
    }
}

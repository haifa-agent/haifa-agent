package io.haifa.agent.model.api;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Ephemeral audio bytes resolved immediately before a provider call. The bytes are never a persisted message value. */
public final class AudioDataPart implements ModelAudioPart {
    public static final int MAXIMUM_BYTES = 10 * 1024 * 1024;
    private static final Set<String> MEDIA_TYPES =
            Set.of("audio/wav", "audio/mp3", "audio/aiff", "audio/aac", "audio/ogg", "audio/flac");

    private final String mediaType;
    private final byte[] bytes;

    public AudioDataPart(String mediaType, byte[] bytes) {
        String normalized = Objects.requireNonNull(mediaType, "mediaType must not be null")
                .trim()
                .toLowerCase(Locale.ROOT);
        if ("audio/mpeg".equals(normalized)) normalized = "audio/mp3";
        if (!MEDIA_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("unsupported audio media type");
        }
        byte[] copied = Objects.requireNonNull(bytes, "bytes must not be null").clone();
        if (copied.length == 0 || copied.length > MAXIMUM_BYTES) {
            throw new IllegalArgumentException("audio data must contain 1 to 10485760 bytes");
        }
        this.mediaType = normalized;
        this.bytes = copied;
    }

    public String mediaType() {
        return mediaType;
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AudioDataPart value
                && mediaType.equals(value.mediaType)
                && Arrays.equals(bytes, value.bytes);
    }

    @Override
    public int hashCode() {
        return 31 * mediaType.hashCode() + Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "AudioDataPart[mediaType=" + mediaType + ", bytes=" + bytes.length + "]";
    }
}

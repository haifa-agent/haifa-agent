package io.haifa.agent.model.api;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Ephemeral image bytes resolved immediately before a provider call. The bytes are never a persisted message value. */
public final class ImageDataPart implements ModelImagePart {
    public static final int MAXIMUM_BYTES = 10 * 1024 * 1024;
    private static final Set<String> MEDIA_TYPES = Set.of("image/png", "image/jpeg", "image/webp", "image/gif");

    private final String mediaType;
    private final byte[] bytes;

    public ImageDataPart(String mediaType, byte[] bytes) {
        String normalized = Objects.requireNonNull(mediaType, "mediaType must not be null")
                .trim()
                .toLowerCase(Locale.ROOT);
        if (!MEDIA_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("unsupported image media type");
        }
        byte[] copied = Objects.requireNonNull(bytes, "bytes must not be null").clone();
        if (copied.length == 0 || copied.length > MAXIMUM_BYTES) {
            throw new IllegalArgumentException("image data must contain 1 to 10485760 bytes");
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

    public int sizeBytes() {
        return bytes.length;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ImageDataPart value
                && mediaType.equals(value.mediaType)
                && Arrays.equals(bytes, value.bytes);
    }

    @Override
    public int hashCode() {
        return 31 * mediaType.hashCode() + Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "ImageDataPart[mediaType=" + mediaType + ", bytes=" + bytes.length + "]";
    }
}

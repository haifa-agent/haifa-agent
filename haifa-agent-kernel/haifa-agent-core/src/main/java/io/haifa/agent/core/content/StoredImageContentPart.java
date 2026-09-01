package io.haifa.agent.core.content;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Stable reference to image bytes owned by an external, product-configured image store. */
public record StoredImageContentPart(
        String storeId, String imageId, String mediaType, long sizeBytes, String sha256, String originalFilename)
        implements ContentPart {
    private static final Set<String> MEDIA_TYPES =
            Set.of("image/png", "image/jpeg", "image/webp", "image/gif", "application/pdf");

    public StoredImageContentPart {
        storeId = text(storeId, "storeId", 128);
        imageId = text(imageId, "imageId", 128);
        mediaType = text(mediaType, "mediaType", 64).toLowerCase(Locale.ROOT);
        if (!MEDIA_TYPES.contains(mediaType)) throw new IllegalArgumentException("unsupported image media type");
        if (sizeBytes < 1 || sizeBytes > 10L * 1024 * 1024) {
            throw new IllegalArgumentException("image size must be between 1 byte and 10 MiB");
        }
        sha256 = text(sha256, "sha256", 71).toLowerCase(Locale.ROOT);
        if (!sha256.matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException("sha256 is invalid");
        originalFilename = text(originalFilename, "originalFilename", 255);
    }

    @Override
    public String contentType() {
        return "stored-image";
    }

    @Override
    public String toString() {
        return "StoredImageContentPart[storeId=" + storeId + ", imageId=" + imageId + ", mediaType=" + mediaType
                + ", sizeBytes=" + sizeBytes + "]";
    }

    private static String text(String value, String field, int limit) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        if (normalized.length() > limit) throw new IllegalArgumentException(field + " is too long");
        return normalized;
    }
}

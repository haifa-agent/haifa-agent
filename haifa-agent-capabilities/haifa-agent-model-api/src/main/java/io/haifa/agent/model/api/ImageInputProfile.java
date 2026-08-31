package io.haifa.agent.model.api;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Immutable, provider-neutral image input constraints for one exact model binding.
 */
public record ImageInputProfile(
        Set<ModelImageSource> allowedSources,
        Set<String> supportedMediaTypes,
        int maxImagesPerRequest,
        long maxBytesPerItem,
        long maxTotalBytes,
        int maxUrlCharacters,
        boolean detailSupported,
        Set<ModelImageDetail> allowedDetails) {

    public static final Set<String> STANDARD_MEDIA_TYPES = Set.of("image/png", "image/jpeg", "image/webp", "image/gif");
    public static final int DEFAULT_MAX_IMAGES = 4;
    public static final long DEFAULT_MAX_BYTES_PER_ITEM = 10 * 1024 * 1024L;
    public static final long DEFAULT_MAX_TOTAL_BYTES = 20 * 1024 * 1024L;
    public static final int DEFAULT_MAX_URL_CHARACTERS = 2048;

    public ImageInputProfile {
        allowedSources = Set.copyOf(Objects.requireNonNull(allowedSources, "allowedSources must not be null"));
        Objects.requireNonNull(supportedMediaTypes, "supportedMediaTypes must not be null");
        supportedMediaTypes = supportedMediaTypes.stream()
                .map(type -> Objects.requireNonNull(type, "mediaType must not be null")
                        .trim()
                        .toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        allowedDetails = Set.copyOf(Objects.requireNonNull(allowedDetails, "allowedDetails must not be null"));
        if (allowedSources.isEmpty()) {
            throw new IllegalArgumentException("allowedSources must not be empty for an active image input profile");
        }
        if (supportedMediaTypes.isEmpty()) {
            throw new IllegalArgumentException(
                    "supportedMediaTypes must not be empty for an active image input profile");
        }
        if (maxImagesPerRequest < 1 || maxBytesPerItem < 1 || maxTotalBytes < maxBytesPerItem || maxUrlCharacters < 1) {
            throw new IllegalArgumentException("invalid image input profile bounds");
        }
        if (detailSupported && allowedDetails.isEmpty()) {
            throw new IllegalArgumentException("detailSupported requires at least one allowed detail level");
        }
        if (!detailSupported && !allowedDetails.isEmpty()) {
            throw new IllegalArgumentException("detailSupported=false must have empty allowed details");
        }
    }

    public static ImageInputProfile standard(Set<ModelImageSource> sources, boolean detailSupported) {
        return new ImageInputProfile(
                sources,
                STANDARD_MEDIA_TYPES,
                DEFAULT_MAX_IMAGES,
                DEFAULT_MAX_BYTES_PER_ITEM,
                DEFAULT_MAX_TOTAL_BYTES,
                DEFAULT_MAX_URL_CHARACTERS,
                detailSupported,
                detailSupported
                        ? Set.of(ModelImageDetail.AUTO, ModelImageDetail.LOW, ModelImageDetail.HIGH)
                        : Set.of());
    }
}

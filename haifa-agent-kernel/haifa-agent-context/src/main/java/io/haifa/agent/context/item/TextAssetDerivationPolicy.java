package io.haifa.agent.context.item;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Frozen limits for the small, deterministic UTF-8 asset text derivation boundary. */
public record TextAssetDerivationPolicy(Set<String> allowedMediaTypes, long maxInputBytes, int maxOutputCharacters) {
    private static final Set<String> SUPPORTED_MEDIA_TYPES = Set.of("text/plain", "text/markdown", "application/json");

    public TextAssetDerivationPolicy {
        allowedMediaTypes = Objects.requireNonNull(allowedMediaTypes, "allowedMediaTypes must not be null").stream()
                .map(TextAssetDerivationPolicy::normalizeMediaType)
                .collect(Collectors.toUnmodifiableSet());
        if (allowedMediaTypes.isEmpty()) {
            throw new IllegalArgumentException("allowedMediaTypes must not be empty");
        }
        if (!SUPPORTED_MEDIA_TYPES.containsAll(allowedMediaTypes)) {
            throw new IllegalArgumentException("allowedMediaTypes contains an unsupported media type");
        }
        if (maxInputBytes < 1 || maxOutputCharacters < 1) {
            throw new IllegalArgumentException("text derivation limits must be positive");
        }
    }

    boolean allows(String mediaType) {
        return allowedMediaTypes.contains(mediaType);
    }

    private static String normalizeMediaType(String value) {
        String normalized = Objects.requireNonNull(value, "allowed media type must not be null")
                .trim()
                .toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.indexOf(';') >= 0) {
            throw new IllegalArgumentException("allowed media type must be a base media type");
        }
        return normalized;
    }
}

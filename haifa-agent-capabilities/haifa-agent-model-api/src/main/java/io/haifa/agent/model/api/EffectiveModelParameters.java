package io.haifa.agent.model.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Provider-neutral parameters resolved by trusted product configuration for one new Run. */
public record EffectiveModelParameters(
        ModelDefinitionId bindingId,
        String profileVersion,
        String profileDigest,
        ModelReasoningPolicy reasoning,
        int maxOutputTokens,
        Optional<ImageInputProfile> imageInput) {
    public static final String PROFILE_VERSION_OPTION = "haifa.model.profile_version";
    public static final String PROFILE_DIGEST_OPTION = "haifa.model.profile_digest";
    public static final String MAX_OUTPUT_TOKENS_OPTION = "max_output_tokens";
    public static final String IMAGE_INPUT_SOURCES_OPTION = "haifa.model.image.allowed_sources";
    public static final String IMAGE_INPUT_MEDIA_TYPES_OPTION = "haifa.model.image.supported_media_types";
    public static final String IMAGE_INPUT_MAX_IMAGES_OPTION = "haifa.model.image.max_images";
    public static final String IMAGE_INPUT_MAX_BYTES_PER_ITEM_OPTION = "haifa.model.image.max_bytes_per_item";
    public static final String IMAGE_INPUT_MAX_TOTAL_BYTES_OPTION = "haifa.model.image.max_total_bytes";
    public static final String IMAGE_INPUT_MAX_URL_CHARS_OPTION = "haifa.model.image.max_url_characters";
    public static final String IMAGE_INPUT_DETAIL_SUPPORTED_OPTION = "haifa.model.image.detail_supported";
    public static final String IMAGE_INPUT_ALLOWED_DETAILS_OPTION = "haifa.model.image.allowed_details";

    public EffectiveModelParameters(
            ModelDefinitionId bindingId,
            String profileVersion,
            String profileDigest,
            ModelReasoningPolicy reasoning,
            int maxOutputTokens) {
        this(bindingId, profileVersion, profileDigest, reasoning, maxOutputTokens, Optional.empty());
    }

    public EffectiveModelParameters {
        bindingId = Objects.requireNonNull(bindingId, "bindingId must not be null");
        profileVersion = ModelValues.text(profileVersion, "profileVersion");
        profileDigest = ModelValues.text(profileDigest, "profileDigest");
        if (!profileDigest.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("profileDigest must be a lowercase SHA-256 digest");
        }
        reasoning = Objects.requireNonNull(reasoning, "reasoning must not be null");
        if (maxOutputTokens < 1) throw new IllegalArgumentException("maxOutputTokens must be positive");
        imageInput = Objects.requireNonNullElse(imageInput, Optional.empty());
    }

    public Map<String, Object> frozenOptions() {
        Map<String, Object> values = new LinkedHashMap<>(reasoning.frozenOptions());
        values.put(PROFILE_VERSION_OPTION, profileVersion);
        values.put(PROFILE_DIGEST_OPTION, profileDigest);
        values.put(MAX_OUTPUT_TOKENS_OPTION, maxOutputTokens);
        imageInput.ifPresent(img -> {
            values.put(
                    IMAGE_INPUT_SOURCES_OPTION,
                    img.allowedSources().stream().map(Enum::name).sorted().toList());
            values.put(
                    IMAGE_INPUT_MEDIA_TYPES_OPTION,
                    img.supportedMediaTypes().stream().sorted().toList());
            values.put(IMAGE_INPUT_MAX_IMAGES_OPTION, img.maxImagesPerRequest());
            values.put(IMAGE_INPUT_MAX_BYTES_PER_ITEM_OPTION, img.maxBytesPerItem());
            values.put(IMAGE_INPUT_MAX_TOTAL_BYTES_OPTION, img.maxTotalBytes());
            values.put(IMAGE_INPUT_MAX_URL_CHARS_OPTION, img.maxUrlCharacters());
            values.put(IMAGE_INPUT_DETAIL_SUPPORTED_OPTION, img.detailSupported());
            values.put(
                    IMAGE_INPUT_ALLOWED_DETAILS_OPTION,
                    img.allowedDetails().stream().map(Enum::name).sorted().toList());
        });
        return Map.copyOf(values);
    }
}

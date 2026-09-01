package io.haifa.agent.model.api;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Complete immutable model and provider configuration frozen into one run. */
public record ResolvedModelSnapshot(
        String schemaVersion,
        ModelProviderId providerId,
        String providerVersion,
        ModelDefinitionId modelId,
        String modelVersion,
        String providerModelId,
        String adapterType,
        String adapterVersion,
        ApiStyleId apiStyle,
        String dialect,
        URI endpoint,
        CredentialRef credentialRef,
        boolean nativeStreaming,
        Set<ModelCapability> capabilities,
        int contextWindow,
        int maxOutputTokens,
        Map<String, Object> providerOptions,
        Map<String, Object> invocationOptions,
        String configurationDigest) {
    public static final String CURRENT_SCHEMA_VERSION = "3.0";

    public ResolvedModelSnapshot {
        schemaVersion = ModelValues.text(schemaVersion, "schemaVersion");
        if (!CURRENT_SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported model snapshot schema: " + schemaVersion);
        }
        providerId = Objects.requireNonNull(providerId, "providerId must not be null");
        providerVersion = ModelValues.text(providerVersion, "providerVersion");
        modelId = Objects.requireNonNull(modelId, "modelId must not be null");
        modelVersion = ModelValues.text(modelVersion, "modelVersion");
        providerModelId = ModelValues.text(providerModelId, "providerModelId");
        adapterType = ModelValues.text(adapterType, "adapterType");
        adapterVersion = ModelValues.text(adapterVersion, "adapterVersion");
        apiStyle = Objects.requireNonNull(apiStyle, "apiStyle must not be null");
        dialect = ModelValues.text(dialect, "dialect");
        endpoint = normalizeEndpoint(endpoint);
        credentialRef = Objects.requireNonNull(credentialRef, "credentialRef must not be null");
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities must not be null"));
        if (capabilities.isEmpty()) throw new IllegalArgumentException("capabilities must not be empty");
        if (contextWindow < 1 || maxOutputTokens < 1 || maxOutputTokens > contextWindow) {
            throw new IllegalArgumentException("frozen model token limits are invalid");
        }
        providerOptions = ModelValues.map(providerOptions, "providerOptions");
        invocationOptions = ModelValues.map(invocationOptions, "invocationOptions");
        configurationDigest = ModelValues.text(configurationDigest, "configurationDigest");
        String expected = digest(
                schemaVersion,
                providerId,
                providerVersion,
                modelId,
                modelVersion,
                providerModelId,
                adapterType,
                adapterVersion,
                apiStyle,
                dialect,
                endpoint,
                credentialRef,
                nativeStreaming,
                capabilities,
                contextWindow,
                maxOutputTokens,
                providerOptions,
                invocationOptions);
        if (!expected.equals(configurationDigest)
                && !legacyDigest(
                                schemaVersion,
                                providerId,
                                providerVersion,
                                modelId,
                                modelVersion,
                                providerModelId,
                                adapterType,
                                adapterVersion,
                                apiStyle,
                                dialect,
                                endpoint,
                                credentialRef,
                                nativeStreaming,
                                capabilities,
                                contextWindow,
                                maxOutputTokens,
                                providerOptions,
                                invocationOptions)
                        .equals(configurationDigest)) {
            throw new IllegalArgumentException("model snapshot configuration digest does not match frozen fields");
        }
    }

    public static ResolvedModelSnapshot create(
            ModelProviderId providerId,
            String providerVersion,
            ModelDefinitionId modelId,
            String modelVersion,
            String providerModelId,
            String adapterType,
            String adapterVersion,
            ApiStyleId apiStyle,
            String dialect,
            URI endpoint,
            CredentialRef credentialRef,
            boolean nativeStreaming,
            Set<ModelCapability> capabilities,
            int contextWindow,
            int maxOutputTokens,
            Map<String, Object> providerOptions,
            Map<String, Object> invocationOptions) {
        URI normalizedEndpoint = normalizeEndpoint(endpoint);
        Map<String, Object> frozenProviderOptions = ModelValues.map(providerOptions, "providerOptions");
        Map<String, Object> frozenInvocationOptions = ModelValues.map(invocationOptions, "invocationOptions");
        Set<ModelCapability> frozenCapabilities = Set.copyOf(capabilities);
        String digest = digest(
                CURRENT_SCHEMA_VERSION,
                providerId,
                providerVersion,
                modelId,
                modelVersion,
                providerModelId,
                adapterType,
                adapterVersion,
                apiStyle,
                dialect,
                normalizedEndpoint,
                credentialRef,
                nativeStreaming,
                frozenCapabilities,
                contextWindow,
                maxOutputTokens,
                frozenProviderOptions,
                frozenInvocationOptions);
        return new ResolvedModelSnapshot(
                CURRENT_SCHEMA_VERSION,
                providerId,
                providerVersion,
                modelId,
                modelVersion,
                providerModelId,
                adapterType,
                adapterVersion,
                apiStyle,
                dialect,
                normalizedEndpoint,
                credentialRef,
                nativeStreaming,
                frozenCapabilities,
                contextWindow,
                maxOutputTokens,
                frozenProviderOptions,
                frozenInvocationOptions,
                digest);
    }

    /** Derives a run-specific snapshot from a trusted, profile-validated parameter set. */
    public ResolvedModelSnapshot withEffectiveParameters(EffectiveModelParameters parameters) {
        Objects.requireNonNull(parameters, "parameters must not be null");
        if (!modelId.equals(parameters.bindingId())) {
            throw new IllegalArgumentException("effective parameters target a different model binding");
        }
        if (parameters.maxOutputTokens() > maxOutputTokens) {
            throw new IllegalArgumentException("effective output token limit exceeds the binding limit");
        }
        Map<String, Object> options = new LinkedHashMap<>(invocationOptions);
        options.keySet()
                .removeAll(Set.of(
                        "thinking",
                        "reasoning_effort",
                        "reasoning_token_budget",
                        EffectiveModelParameters.PROFILE_VERSION_OPTION,
                        EffectiveModelParameters.PROFILE_DIGEST_OPTION,
                        EffectiveModelParameters.MAX_OUTPUT_TOKENS_OPTION,
                        EffectiveModelParameters.IMAGE_INPUT_SOURCES_OPTION,
                        EffectiveModelParameters.IMAGE_INPUT_MEDIA_TYPES_OPTION,
                        EffectiveModelParameters.IMAGE_INPUT_MAX_IMAGES_OPTION,
                        EffectiveModelParameters.IMAGE_INPUT_MAX_BYTES_PER_ITEM_OPTION,
                        EffectiveModelParameters.IMAGE_INPUT_MAX_TOTAL_BYTES_OPTION,
                        EffectiveModelParameters.IMAGE_INPUT_MAX_URL_CHARS_OPTION,
                        EffectiveModelParameters.IMAGE_INPUT_DETAIL_SUPPORTED_OPTION,
                        EffectiveModelParameters.IMAGE_INPUT_ALLOWED_DETAILS_OPTION));
        options.putAll(parameters.frozenOptions());
        return create(
                providerId,
                providerVersion,
                modelId,
                modelVersion,
                providerModelId,
                adapterType,
                adapterVersion,
                apiStyle,
                dialect,
                endpoint,
                credentialRef,
                nativeStreaming,
                capabilities,
                contextWindow,
                parameters.maxOutputTokens(),
                providerOptions,
                options);
    }

    /** Returns the frozen image input constraints if image input is active for this model. */
    public java.util.Optional<ImageInputProfile> imageInput() {
        return frozenImageInputProfile();
    }

    /** Returns the frozen image input constraints if image input is active for this model. */
    public java.util.Optional<ImageInputProfile> frozenImageInputProfile() {
        if (invocationOptions.containsKey(EffectiveModelParameters.IMAGE_INPUT_MAX_IMAGES_OPTION)) {
            try {
                @SuppressWarnings("unchecked")
                List<String> sourceNames =
                        (List<String>) invocationOptions.get(EffectiveModelParameters.IMAGE_INPUT_SOURCES_OPTION);
                Set<ModelImageSource> sources = sourceNames == null
                        ? Set.of()
                        : sourceNames.stream()
                                .map(ModelImageSource::valueOf)
                                .collect(java.util.stream.Collectors.toSet());

                @SuppressWarnings("unchecked")
                List<String> mediaTypesList =
                        (List<String>) invocationOptions.get(EffectiveModelParameters.IMAGE_INPUT_MEDIA_TYPES_OPTION);
                Set<String> mediaTypes = mediaTypesList == null ? Set.of() : Set.copyOf(mediaTypesList);

                int maxImages = ((Number) invocationOptions.get(EffectiveModelParameters.IMAGE_INPUT_MAX_IMAGES_OPTION))
                        .intValue();
                long maxBytesPerItem = ((Number)
                                invocationOptions.get(EffectiveModelParameters.IMAGE_INPUT_MAX_BYTES_PER_ITEM_OPTION))
                        .longValue();
                long maxTotalBytes = ((Number)
                                invocationOptions.get(EffectiveModelParameters.IMAGE_INPUT_MAX_TOTAL_BYTES_OPTION))
                        .longValue();
                int maxUrlChars = ((Number)
                                invocationOptions.get(EffectiveModelParameters.IMAGE_INPUT_MAX_URL_CHARS_OPTION))
                        .intValue();
                boolean detailSupported = Boolean.TRUE.equals(
                        invocationOptions.get(EffectiveModelParameters.IMAGE_INPUT_DETAIL_SUPPORTED_OPTION));

                @SuppressWarnings("unchecked")
                List<String> detailNames = (List<String>)
                        invocationOptions.get(EffectiveModelParameters.IMAGE_INPUT_ALLOWED_DETAILS_OPTION);
                Set<ModelImageDetail> details = detailNames == null
                        ? Set.of()
                        : detailNames.stream()
                                .map(ModelImageDetail::valueOf)
                                .collect(java.util.stream.Collectors.toSet());

                return java.util.Optional.of(new ImageInputProfile(
                        sources,
                        mediaTypes,
                        maxImages,
                        maxBytesPerItem,
                        maxTotalBytes,
                        maxUrlChars,
                        detailSupported,
                        details));
            } catch (Exception e) {
                return java.util.Optional.empty();
            }
        }
        Set<ModelImageSource> sources = new java.util.HashSet<>();
        if (capabilities.contains(ModelCapability.IMAGE_UPLOAD_INPUT)) sources.add(ModelImageSource.UPLOAD);
        if (capabilities.contains(ModelCapability.IMAGE_URL_INPUT)) sources.add(ModelImageSource.URL);
        if (sources.isEmpty()) {
            return java.util.Optional.empty();
        }
        boolean isGemini = ModelApiStyles.GOOGLE_GEMINI_GENERATE_CONTENT.equals(apiStyle)
                || "google-antigravity".equals(providerId.value());
        return java.util.Optional.of(
                isGemini
                        ? ImageInputProfile.gemini(sources)
                        : ImageInputProfile.standard(sources, sources.contains(ModelImageSource.URL)));
    }

    private static URI normalizeEndpoint(URI endpoint) {
        URI normalized =
                Objects.requireNonNull(endpoint, "endpoint must not be null").normalize();
        if (!normalized.isAbsolute() || normalized.getHost() == null) {
            throw new IllegalArgumentException("endpoint must be an absolute network URI");
        }
        String value = normalized.toString();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return URI.create(value);
    }

    private static String digest(
            String schemaVersion,
            ModelProviderId providerId,
            String providerVersion,
            ModelDefinitionId modelId,
            String modelVersion,
            String providerModelId,
            String adapterType,
            String adapterVersion,
            ApiStyleId apiStyle,
            String dialect,
            URI endpoint,
            CredentialRef credentialRef,
            boolean nativeStreaming,
            Set<ModelCapability> capabilities,
            int contextWindow,
            int maxOutputTokens,
            Map<String, Object> providerOptions,
            Map<String, Object> invocationOptions) {
        return digest(
                schemaVersion,
                providerId,
                providerVersion,
                modelId,
                modelVersion,
                providerModelId,
                adapterType,
                adapterVersion,
                apiStyle,
                dialect,
                endpoint,
                credentialRef,
                nativeStreaming,
                capabilities,
                contextWindow,
                maxOutputTokens,
                providerOptions,
                invocationOptions,
                false);
    }

    private static String legacyDigest(
            String schemaVersion,
            ModelProviderId providerId,
            String providerVersion,
            ModelDefinitionId modelId,
            String modelVersion,
            String providerModelId,
            String adapterType,
            String adapterVersion,
            ApiStyleId apiStyle,
            String dialect,
            URI endpoint,
            CredentialRef credentialRef,
            boolean nativeStreaming,
            Set<ModelCapability> capabilities,
            int contextWindow,
            int maxOutputTokens,
            Map<String, Object> providerOptions,
            Map<String, Object> invocationOptions) {
        return digest(
                schemaVersion,
                providerId,
                providerVersion,
                modelId,
                modelVersion,
                providerModelId,
                adapterType,
                adapterVersion,
                apiStyle,
                dialect,
                endpoint,
                credentialRef,
                nativeStreaming,
                capabilities,
                contextWindow,
                maxOutputTokens,
                providerOptions,
                invocationOptions,
                true);
    }

    private static String digest(
            String schemaVersion,
            ModelProviderId providerId,
            String providerVersion,
            ModelDefinitionId modelId,
            String modelVersion,
            String providerModelId,
            String adapterType,
            String adapterVersion,
            ApiStyleId apiStyle,
            String dialect,
            URI endpoint,
            CredentialRef credentialRef,
            boolean nativeStreaming,
            Set<ModelCapability> capabilities,
            int contextWindow,
            int maxOutputTokens,
            Map<String, Object> providerOptions,
            Map<String, Object> invocationOptions,
            boolean legacyNumberTypes) {
        String canonical = String.join(
                "|",
                schemaVersion,
                providerId.value(),
                providerVersion,
                modelId.value(),
                modelVersion,
                providerModelId,
                adapterType,
                adapterVersion,
                apiStyle.value(),
                dialect,
                endpoint.toString(),
                credentialRef.value(),
                Boolean.toString(nativeStreaming),
                capabilities.stream().map(Enum::name).sorted().toList().toString(),
                Integer.toString(contextWindow),
                Integer.toString(maxOutputTokens),
                canonicalMap(providerOptions, legacyNumberTypes),
                canonicalMap(invocationOptions, legacyNumberTypes));
        return sha256(canonical);
    }

    private static String sha256(String canonical) {
        try {
            byte[] value = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static String canonicalMap(Map<String, Object> values, boolean legacyNumberTypes) {
        List<String> entries = new ArrayList<>();
        values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry ->
                        entries.add(entry.getKey() + "=" + canonicalValue(entry.getValue(), legacyNumberTypes)));
        return "{" + String.join(",", entries) + "}";
    }

    private static String canonicalValue(Object value, boolean legacyNumberTypes) {
        if (value instanceof Number number) {
            return legacyNumberTypes
                    ? value.getClass().getName() + ":" + value
                    : Number.class.getName() + ":" + canonicalNumber(number);
        }
        if (value instanceof String
                || value instanceof Boolean
                || value instanceof Enum<?>
                || value instanceof URI
                || value instanceof java.time.Duration) {
            return value.getClass().getName() + ":" + value;
        }
        if (value instanceof Map<?, ?> map) {
            List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            entries.sort(Comparator.comparing(entry -> String.valueOf(entry.getKey())));
            return entries.stream()
                    .map(entry ->
                            String.valueOf(entry.getKey()) + "=" + canonicalValue(entry.getValue(), legacyNumberTypes))
                    .toList()
                    .toString();
        }
        if (value instanceof Set<?> set) {
            return set.stream()
                    .map(item -> canonicalValue(item, legacyNumberTypes))
                    .sorted()
                    .toList()
                    .toString();
        }
        if (value instanceof Iterable<?> iterable) {
            List<String> items = new ArrayList<>();
            iterable.forEach(item -> items.add(canonicalValue(item, legacyNumberTypes)));
            return items.toString();
        }
        throw new IllegalArgumentException(
                "unsupported frozen model option type: " + value.getClass().getName());
    }

    private static String canonicalNumber(Number value) {
        try {
            return new java.math.BigDecimal(value.toString())
                    .stripTrailingZeros()
                    .toPlainString();
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "unsupported frozen model option number: "
                            + value.getClass().getName(),
                    exception);
        }
    }
}

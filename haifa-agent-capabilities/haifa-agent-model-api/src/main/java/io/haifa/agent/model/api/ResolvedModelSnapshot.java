package io.haifa.agent.model.api;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
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
        URI endpoint,
        CredentialRef credentialRef,
        Set<ModelCapability> capabilities,
        int contextWindow,
        int maxOutputTokens,
        Map<String, Object> providerOptions,
        Map<String, Object> invocationOptions,
        String configurationDigest) {
    public static final String CURRENT_SCHEMA_VERSION = "2.0";

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
                endpoint,
                credentialRef,
                capabilities,
                contextWindow,
                maxOutputTokens,
                providerOptions,
                invocationOptions);
        if (!expected.equals(configurationDigest)) {
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
            URI endpoint,
            CredentialRef credentialRef,
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
                normalizedEndpoint,
                credentialRef,
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
                normalizedEndpoint,
                credentialRef,
                frozenCapabilities,
                contextWindow,
                maxOutputTokens,
                frozenProviderOptions,
                frozenInvocationOptions,
                digest);
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
            URI endpoint,
            CredentialRef credentialRef,
            Set<ModelCapability> capabilities,
            int contextWindow,
            int maxOutputTokens,
            Map<String, Object> providerOptions,
            Map<String, Object> invocationOptions) {
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
                endpoint.toString(),
                credentialRef.value(),
                capabilities.stream().map(Enum::name).sorted().toList().toString(),
                Integer.toString(contextWindow),
                Integer.toString(maxOutputTokens),
                canonicalMap(providerOptions),
                canonicalMap(invocationOptions));
        try {
            byte[] value = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static String canonicalMap(Map<String, Object> values) {
        List<String> entries = new ArrayList<>();
        values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> entries.add(entry.getKey() + "=" + canonicalValue(entry.getValue())));
        return "{" + String.join(",", entries) + "}";
    }

    private static String canonicalValue(Object value) {
        if (value instanceof String
                || value instanceof Number
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
                    .map(entry -> String.valueOf(entry.getKey()) + "=" + canonicalValue(entry.getValue()))
                    .toList()
                    .toString();
        }
        if (value instanceof Set<?> set) {
            return set.stream()
                    .map(ResolvedModelSnapshot::canonicalValue)
                    .sorted()
                    .toList()
                    .toString();
        }
        if (value instanceof Iterable<?> iterable) {
            List<String> items = new ArrayList<>();
            iterable.forEach(item -> items.add(canonicalValue(item)));
            return items.toString();
        }
        throw new IllegalArgumentException(
                "unsupported frozen model option type: " + value.getClass().getName());
    }
}

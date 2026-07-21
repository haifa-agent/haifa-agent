package io.haifa.agent.model.core;

import io.haifa.agent.model.api.ModelDefinition;
import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ModelStatus;
import io.haifa.agent.model.api.ProviderStatus;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
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

/** Selects exactly the requested model with no implicit routing or fallback. */
public final class DeterministicModelSelector {
    private final ModelCatalog catalog;
    private final ModelAccessPolicy accessPolicy;
    private final String adapterVersion;

    public DeterministicModelSelector(ModelCatalog catalog, ModelAccessPolicy accessPolicy, String adapterVersion) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
        this.adapterVersion = requireText(adapterVersion, "adapterVersion");
    }

    public ResolvedModelSnapshot select(ModelSelectionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        ModelDefinition model = catalog.model(request.modelId())
                .orElseThrow(() -> new ModelSelectionException(
                        ModelSelectionFailure.MODEL_NOT_FOUND, "model is not configured: " + request.modelId()));
        ModelProviderDefinition provider = catalog.provider(model.providerId())
                .orElseThrow(() -> new ModelSelectionException(
                        ModelSelectionFailure.PROVIDER_NOT_FOUND, "provider is not configured: " + model.providerId()));
        if (provider.status() != ProviderStatus.ACTIVE) {
            throw new ModelSelectionException(
                    ModelSelectionFailure.PROVIDER_NOT_ACTIVE, "provider is not active: " + provider.id());
        }
        if (model.status() != ModelStatus.ACTIVE) {
            throw new ModelSelectionException(
                    ModelSelectionFailure.MODEL_NOT_ACTIVE, "model is not active: " + model.id());
        }
        if (!model.capabilities().containsAll(request.requiredCapabilities())) {
            throw new ModelSelectionException(
                    ModelSelectionFailure.CAPABILITY_MISMATCH, "model does not satisfy required capabilities");
        }
        if (!accessPolicy.allowed(request, provider, model)) {
            throw new ModelSelectionException(ModelSelectionFailure.ACCESS_DENIED, "model access is denied");
        }
        String canonical = provider.id() + "|" + provider.adapterType() + "|" + adapterVersion + "|"
                + provider.endpoint() + "|"
                + provider.credentialRef() + "|" + model.id() + "|" + model.providerModelId() + "|"
                + model.capabilities().stream().map(Enum::name).sorted().toList() + "|"
                + canonicalMap(provider.options()) + "|" + canonicalMap(model.options());
        LinkedHashMap<String, Object> invocationOptions = new LinkedHashMap<>(model.options());
        invocationOptions.put("maxOutputTokens", model.maxOutputTokens());
        return new ResolvedModelSnapshot(
                provider.id(),
                model.id(),
                model.providerModelId(),
                provider.adapterType(),
                adapterVersion,
                provider.credentialRef(),
                model.capabilities(),
                invocationOptions,
                sha256(canonical));
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
                || value instanceof Enum<?>) {
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
                    .map(DeterministicModelSelector::canonicalValue)
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
                "unsupported model option type: " + value.getClass().getName());
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}

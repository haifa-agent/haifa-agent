package io.haifa.agent.sdk.product;

import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.model.api.EffectiveModelParameters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Trusted named Run profile with frozen model invocation options. */
public record ProductRunProfile(
        String id,
        String version,
        String modelId,
        AgentRunType runType,
        AgentRunBudget budget,
        AgentRunLimits limits,
        Map<String, Object> modelRequestOptions,
        Optional<EffectiveModelParameters> effectiveModelParameters,
        Optional<Set<String>> allowedTools) {
    public ProductRunProfile(
            String id,
            String version,
            String modelId,
            AgentRunType runType,
            AgentRunBudget budget,
            AgentRunLimits limits,
            Map<String, Object> modelRequestOptions) {
        this(id, version, modelId, runType, budget, limits, modelRequestOptions, Optional.empty(), Optional.empty());
    }

    public ProductRunProfile(
            String id,
            String version,
            String modelId,
            AgentRunType runType,
            AgentRunBudget budget,
            AgentRunLimits limits,
            Map<String, Object> modelRequestOptions,
            Optional<Set<String>> allowedTools) {
        this(id, version, modelId, runType, budget, limits, modelRequestOptions, Optional.empty(), allowedTools);
    }

    public ProductRunProfile {
        id = ProductValues.text(id, "id", 128);
        version = ProductValues.text(version, "version", 64);
        modelId = ProductValues.text(modelId, "modelId", 128);
        runType = Objects.requireNonNull(runType, "runType must not be null");
        budget = Objects.requireNonNull(budget, "budget must not be null");
        limits = Objects.requireNonNull(limits, "limits must not be null");
        modelRequestOptions =
                freezeMap(Objects.requireNonNull(modelRequestOptions, "modelRequestOptions must not be null"));
        effectiveModelParameters =
                Objects.requireNonNull(effectiveModelParameters, "effectiveModelParameters must not be null");
        if (effectiveModelParameters.isPresent()
                && !effectiveModelParameters.orElseThrow().bindingId().value().equals(modelId)) {
            throw new IllegalArgumentException("effectiveModelParameters must target modelId");
        }
        allowedTools = Objects.requireNonNull(allowedTools, "allowedTools must not be null")
                .map(ProductRunProfile::freezeToolAliases);
    }

    private static Set<String> freezeToolAliases(Set<String> aliases) {
        return aliases.stream()
                .map(alias -> ProductValues.text(alias, "allowedTools alias", 128))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static Map<String, Object> freezeMap(Map<?, ?> source) {
        Map<String, Object> frozen = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.isBlank()) {
                throw new IllegalArgumentException("modelRequestOptions keys must be non-blank strings");
            }
            frozen.put(key, freezeValue(entry.getValue()));
        }
        return Map.copyOf(frozen);
    }

    private static Object freezeValue(Object value) {
        if (value instanceof Map<?, ?> map) return freezeMap(map);
        if (value instanceof Iterable<?> iterable) {
            List<Object> frozen = new ArrayList<>();
            iterable.forEach(item -> frozen.add(freezeValue(item)));
            return List.copyOf(frozen);
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        throw new IllegalArgumentException("modelRequestOptions values must be JSON-compatible and non-null");
    }
}

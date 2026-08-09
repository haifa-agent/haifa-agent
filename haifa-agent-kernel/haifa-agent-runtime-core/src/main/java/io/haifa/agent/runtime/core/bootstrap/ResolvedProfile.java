package io.haifa.agent.runtime.core.bootstrap;

import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record ResolvedProfile(
        String id,
        String version,
        AgentRunType runType,
        AgentRunBudget budget,
        AgentRunLimits limits,
        ResolvedModelSnapshot model,
        Map<String, ResolvedCapability> capabilities,
        Map<String, Object> modelRequestOptions,
        Optional<Set<String>> allowedTools) {
    public ResolvedProfile(
            String id,
            String version,
            AgentRunType runType,
            AgentRunBudget budget,
            AgentRunLimits limits,
            ResolvedModelSnapshot model) {
        this(id, version, runType, budget, limits, model, Map.of(), Map.of(), Optional.empty());
    }

    public ResolvedProfile(
            String id,
            String version,
            AgentRunType runType,
            AgentRunBudget budget,
            AgentRunLimits limits,
            ResolvedModelSnapshot model,
            Map<String, ResolvedCapability> capabilities) {
        this(id, version, runType, budget, limits, model, capabilities, Map.of(), Optional.empty());
    }

    public ResolvedProfile(
            String id,
            String version,
            AgentRunType runType,
            AgentRunBudget budget,
            AgentRunLimits limits,
            ResolvedModelSnapshot model,
            Map<String, ResolvedCapability> capabilities,
            Map<String, Object> modelRequestOptions) {
        this(id, version, runType, budget, limits, model, capabilities, modelRequestOptions, Optional.empty());
    }

    public ResolvedProfile(
            String id, String version, AgentRunType runType, AgentRunBudget budget, AgentRunLimits limits) {
        this(
                id,
                version,
                runType,
                budget,
                limits,
                DefaultResolvedModelSnapshots.deepSeekV4Pro(),
                Map.of(),
                Map.of(),
                Optional.empty());
    }

    public ResolvedProfile {
        id = requireText(id, "id");
        version = requireText(version, "version");
        runType = Objects.requireNonNull(runType, "runType must not be null");
        budget = Objects.requireNonNull(budget, "budget must not be null");
        limits = Objects.requireNonNull(limits, "limits must not be null");
        model = Objects.requireNonNull(model, "model must not be null");
        capabilities = Map.copyOf(Objects.requireNonNull(capabilities, "capabilities must not be null"));
        capabilities.forEach((key, value) -> {
            if (!key.equals(value.capabilityId())) {
                throw new IllegalArgumentException("capability map key must match capabilityId");
            }
        });
        modelRequestOptions = ModelRequestOptions.freeze(modelRequestOptions);
        allowedTools = Objects.requireNonNull(allowedTools, "allowedTools must not be null")
                .map(aliases -> aliases.stream()
                        .map(alias -> requireText(alias, "allowedTools alias"))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}

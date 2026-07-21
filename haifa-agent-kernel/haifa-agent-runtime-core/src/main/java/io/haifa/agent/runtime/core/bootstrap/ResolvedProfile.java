package io.haifa.agent.runtime.core.bootstrap;

import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.util.Objects;

public record ResolvedProfile(
        String id,
        String version,
        AgentRunType runType,
        AgentRunBudget budget,
        AgentRunLimits limits,
        ResolvedModelSnapshot model) {
    public ResolvedProfile(
            String id, String version, AgentRunType runType, AgentRunBudget budget, AgentRunLimits limits) {
        this(id, version, runType, budget, limits, DefaultResolvedModelSnapshots.deepSeekV4Pro());
    }

    public ResolvedProfile {
        id = requireText(id, "id");
        version = requireText(version, "version");
        runType = Objects.requireNonNull(runType, "runType must not be null");
        budget = Objects.requireNonNull(budget, "budget must not be null");
        limits = Objects.requireNonNull(limits, "limits must not be null");
        model = Objects.requireNonNull(model, "model must not be null");
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}

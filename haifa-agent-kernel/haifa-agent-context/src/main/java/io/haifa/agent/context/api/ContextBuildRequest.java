package io.haifa.agent.context.api;

import io.haifa.agent.context.item.ContextItem;
import io.haifa.agent.context.prompt.PromptComponent;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunUsage;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.util.List;
import java.util.Objects;

/** Runtime-internal trusted request; it is never populated from public request identity fields. */
public record ContextBuildRequest(
        AgentRunId runId,
        AgentSessionId sessionId,
        TenantRef tenant,
        PrincipalRef principal,
        int iteration,
        ResolvedModelSnapshot model,
        AgentRunBudget runBudget,
        AgentRunUsage runUsage,
        List<PromptComponent> prompts,
        List<ContextItem> items,
        List<ModelToolSpecification> tools,
        int requestedOutputTokens,
        int safetyMarginTokens) {
    public ContextBuildRequest {
        runId = Objects.requireNonNull(runId, "runId must not be null");
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        principal = Objects.requireNonNull(principal, "principal must not be null");
        if (iteration < 1) throw new IllegalArgumentException("iteration must be positive");
        model = Objects.requireNonNull(model, "model must not be null");
        runBudget = Objects.requireNonNull(runBudget, "runBudget must not be null");
        runUsage = Objects.requireNonNull(runUsage, "runUsage must not be null");
        prompts = List.copyOf(Objects.requireNonNull(prompts, "prompts must not be null"));
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        tools = List.copyOf(Objects.requireNonNull(tools, "tools must not be null"));
        if (requestedOutputTokens < 1) throw new IllegalArgumentException("requestedOutputTokens must be positive");
        if (safetyMarginTokens < 0) throw new IllegalArgumentException("safetyMarginTokens must not be negative");
    }
}

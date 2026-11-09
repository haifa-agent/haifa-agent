package io.haifa.agent.runtime.core.model;

import io.haifa.agent.context.api.AgentContext;
import io.haifa.agent.context.budget.ContextWindowBudget;

/** Per-call projection, not persisted. Its opaque payload stays inside the model boundary. */
public final class PreparedModelInput {
    private final AgentContext source;
    private final ModelMessageAssembler.AssemblyResult assembly;
    private final long estimatedTokens, fixedTokens, otherTokens;

    PreparedModelInput(
            AgentContext source,
            ModelMessageAssembler.AssemblyResult assembly,
            long estimatedTokens,
            long fixedTokens,
            long otherTokens) {
        this.source = source;
        this.assembly = assembly;
        this.estimatedTokens = estimatedTokens;
        this.fixedTokens = fixedTokens;
        this.otherTokens = otherTokens;
        if (fixedTokens < 0 || otherTokens < 0 || fixedTokens + otherTokens > estimatedTokens)
            throw new IllegalArgumentException("invalid prepared input accounting");
    }

    public ContextWindowBudget budget() {
        return source.budget();
    }

    public long estimatedTokens() {
        return estimatedTokens;
    }

    public long fixedTokens() {
        return fixedTokens;
    }

    public long otherTokens() {
        return otherTokens;
    }

    public long sessionTokens() {
        return estimatedTokens - fixedTokens - otherTokens;
    }

    ModelMessageAssembler.AssemblyResult assembly() {
        return assembly;
    }

    boolean matches(AgentContext context) {
        return source.prompts().equals(context.prompts())
                && source.items().equals(context.items())
                && source.tools().equals(context.tools())
                && source.budget().equals(context.budget());
    }

    @Override
    public String toString() {
        return "PreparedModelInput[estimatedTokens=" + estimatedTokens + "]";
    }
}

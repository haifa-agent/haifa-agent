package io.haifa.agent.runtime.core.guard;

import io.haifa.agent.core.run.AgentRun;

/** Typed Runtime hard-limit signal with safe, persistence-ready usage facts. */
public final class RuntimeLimitExceededException extends IllegalStateException {
    private final String resource;
    private final long limit;
    private final long used;

    public RuntimeLimitExceededException(String resource, long limit, long used) {
        super(resource + " limit exceeded");
        if (resource == null || resource.isBlank()) throw new IllegalArgumentException("resource must not be blank");
        if (limit < 0 || used < 0) throw new IllegalArgumentException("limit and used must not be negative");
        this.resource = resource.strip();
        this.limit = limit;
        this.used = used;
    }

    public String resource() {
        return resource;
    }

    public long limit() {
        return limit;
    }

    public long used() {
        return used;
    }

    public static RuntimeLimitExceededException forRunBudget(AgentRun run) {
        var budget = run.budget();
        var usage = run.usage();
        if (usage.inputTokens() > budget.maxInputTokens()) {
            return new RuntimeLimitExceededException("inputTokens", budget.maxInputTokens(), usage.inputTokens());
        }
        if (usage.outputTokens() > budget.maxOutputTokens()) {
            return new RuntimeLimitExceededException("outputTokens", budget.maxOutputTokens(), usage.outputTokens());
        }
        if (usage.cachedInputTokens() > budget.maxCachedInputTokens()) {
            return new RuntimeLimitExceededException(
                    "cachedInputTokens", budget.maxCachedInputTokens(), usage.cachedInputTokens());
        }
        if (usage.toolCalls() > budget.maxToolCalls()) {
            return new RuntimeLimitExceededException("toolCalls", budget.maxToolCalls(), usage.toolCalls());
        }
        if (usage.modelCalls() > budget.maxModelCalls()) {
            return new RuntimeLimitExceededException("modelCalls", budget.maxModelCalls(), usage.modelCalls());
        }
        if (usage.childRuns() > budget.maxChildRuns()) {
            return new RuntimeLimitExceededException("childRuns", budget.maxChildRuns(), usage.childRuns());
        }
        if (usage.costMinorUnits() > budget.maxCostMinorUnits()) {
            return new RuntimeLimitExceededException(
                    "costMinorUnits", budget.maxCostMinorUnits(), usage.costMinorUnits());
        }
        return new RuntimeLimitExceededException("runBudget", 0, 0);
    }
}

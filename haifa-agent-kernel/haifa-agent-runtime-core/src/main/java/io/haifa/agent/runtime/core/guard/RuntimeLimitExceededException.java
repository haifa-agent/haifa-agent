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

    public static RuntimeLimitExceededException forRunLimits(AgentRun run) {
        var limits = run.limits();
        var usage = run.usage();
        if (usage.modelCalls() >= limits.maxModelCalls()) {
            return new RuntimeLimitExceededException("modelCalls", limits.maxModelCalls(), usage.modelCalls());
        }
        if (usage.toolCalls() > limits.maxToolCalls()) {
            return new RuntimeLimitExceededException("toolCalls", limits.maxToolCalls(), usage.toolCalls());
        }
        if (usage.childRuns() > limits.maxChildRuns()) {
            return new RuntimeLimitExceededException("childRuns", limits.maxChildRuns(), usage.childRuns());
        }
        return new RuntimeLimitExceededException("runLimits", 0, 0);
    }

    public static RuntimeLimitExceededException forRunBudget(AgentRun run) {
        var quota = run.quotaPolicy();
        var limits = run.limits();
        var usage = run.usage();
        if (usage.modelCalls() >= limits.maxModelCalls()) {
            return new RuntimeLimitExceededException("modelCalls", limits.maxModelCalls(), usage.modelCalls());
        }
        if (usage.toolCalls() > limits.maxToolCalls()) {
            return new RuntimeLimitExceededException("toolCalls", limits.maxToolCalls(), usage.toolCalls());
        }
        if (usage.childRuns() > limits.maxChildRuns()) {
            return new RuntimeLimitExceededException("childRuns", limits.maxChildRuns(), usage.childRuns());
        }
        if (quota.mode() == io.haifa.agent.core.run.QuotaMode.HARD_STOP) {
            if (quota.maxInputTokens() != null
                    && quota.maxInputTokens() > 0
                    && usage.inputTokens() > quota.maxInputTokens()) {
                return new RuntimeLimitExceededException("inputTokens", quota.maxInputTokens(), usage.inputTokens());
            }
            if (quota.maxOutputTokens() != null
                    && quota.maxOutputTokens() > 0
                    && usage.outputTokens() > quota.maxOutputTokens()) {
                return new RuntimeLimitExceededException("outputTokens", quota.maxOutputTokens(), usage.outputTokens());
            }
            if (quota.maxCachedInputTokens() != null
                    && quota.maxCachedInputTokens() > 0
                    && usage.cachedInputTokens() > quota.maxCachedInputTokens()) {
                return new RuntimeLimitExceededException(
                        "cachedInputTokens", quota.maxCachedInputTokens(), usage.cachedInputTokens());
            }
            if (quota.maxCostMinorUnits() != null
                    && quota.maxCostMinorUnits() > 0
                    && usage.costMinorUnits() > quota.maxCostMinorUnits()) {
                return new RuntimeLimitExceededException(
                        "costMinorUnits", quota.maxCostMinorUnits(), usage.costMinorUnits());
            }
        }
        return new RuntimeLimitExceededException("runBudget", 0, 0);
    }
}

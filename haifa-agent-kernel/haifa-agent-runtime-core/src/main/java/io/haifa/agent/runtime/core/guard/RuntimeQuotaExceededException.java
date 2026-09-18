package io.haifa.agent.runtime.core.guard;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.QuotaMode;

/** Typed runtime signal when a governed cumulative consumable quota is exceeded. */
public final class RuntimeQuotaExceededException extends IllegalStateException {
    private final String resource;
    private final long limit;
    private final long used;

    public RuntimeQuotaExceededException(String resource, long limit, long used) {
        super(resource + " quota exceeded");
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

    public static RuntimeQuotaExceededException forRunQuota(AgentRun run) {
        var quota = run.quotaPolicy();
        var usage = run.usage();
        if (quota.mode() == QuotaMode.HARD_STOP) {
            if (quota.maxInputTokens() != null
                    && quota.maxInputTokens() > 0
                    && usage.inputTokens() > quota.maxInputTokens()) {
                return new RuntimeQuotaExceededException("inputTokens", quota.maxInputTokens(), usage.inputTokens());
            }
            if (quota.maxOutputTokens() != null
                    && quota.maxOutputTokens() > 0
                    && usage.outputTokens() > quota.maxOutputTokens()) {
                return new RuntimeQuotaExceededException("outputTokens", quota.maxOutputTokens(), usage.outputTokens());
            }
            if (quota.maxCachedInputTokens() != null
                    && quota.maxCachedInputTokens() > 0
                    && usage.cachedInputTokens() > quota.maxCachedInputTokens()) {
                return new RuntimeQuotaExceededException(
                        "cachedInputTokens", quota.maxCachedInputTokens(), usage.cachedInputTokens());
            }
            if (quota.maxCostMinorUnits() != null
                    && quota.maxCostMinorUnits() > 0
                    && usage.costMinorUnits() > quota.maxCostMinorUnits()) {
                return new RuntimeQuotaExceededException(
                        "costMinorUnits", quota.maxCostMinorUnits(), usage.costMinorUnits());
            }
        }
        return new RuntimeQuotaExceededException("runQuota", 0, 0);
    }
}

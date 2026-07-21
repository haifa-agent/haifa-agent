package io.haifa.agent.core.run;

/** Structural and time limits distinct from consumable budget. */
public record AgentRunLimits(
        int maxIterations, int maxDepth, int maxParallelChildren, long maxWallTimeMillis, long maxIdleTimeMillis) {
    public AgentRunLimits {
        if (maxIterations < 1
                || maxDepth < 0
                || maxParallelChildren < 1
                || maxWallTimeMillis < 1
                || maxIdleTimeMillis < 1) {
            throw new IllegalArgumentException("run limits must be positive, except maxDepth which may be zero");
        }
    }
}

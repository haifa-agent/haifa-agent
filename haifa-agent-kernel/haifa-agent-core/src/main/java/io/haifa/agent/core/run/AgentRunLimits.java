package io.haifa.agent.core.run;

/** Structural and time execution limits distinct from cumulative quota. */
public record AgentRunLimits(
        int maxIterations,
        int maxDepth,
        int maxParallelChildren,
        long maxWallTimeMillis,
        long maxIdleTimeMillis,
        long maxModelCalls,
        long maxToolCalls,
        long maxChildRuns) {

    public static final long DEFAULT_MAX_MODEL_CALLS = 64L;
    public static final long DEFAULT_MAX_TOOL_CALLS = 32L;
    public static final long DEFAULT_MAX_CHILD_RUNS = 8L;

    public AgentRunLimits {
        if (maxIterations < 1
                || maxDepth < 0
                || maxParallelChildren < 1
                || maxWallTimeMillis < 1
                || maxIdleTimeMillis < 1
                || maxModelCalls < 1
                || maxToolCalls < 0
                || maxChildRuns < 0) {
            throw new IllegalArgumentException("run limits values are invalid");
        }
    }

    public AgentRunLimits(
            int maxIterations, int maxDepth, int maxParallelChildren, long maxWallTimeMillis, long maxIdleTimeMillis) {
        this(
                maxIterations,
                maxDepth,
                maxParallelChildren,
                maxWallTimeMillis,
                maxIdleTimeMillis,
                DEFAULT_MAX_MODEL_CALLS,
                DEFAULT_MAX_TOOL_CALLS,
                DEFAULT_MAX_CHILD_RUNS);
    }
}

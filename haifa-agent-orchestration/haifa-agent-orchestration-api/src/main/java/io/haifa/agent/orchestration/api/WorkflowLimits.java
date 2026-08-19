package io.haifa.agent.orchestration.api;

public record WorkflowLimits(int maximumIterationsPerNode, int maximumNodes, int maximumParallelBranches) {
    public WorkflowLimits {
        if (maximumIterationsPerNode < 1 || maximumNodes < 1 || maximumParallelBranches < 1) {
            throw new IllegalArgumentException("workflow limits must be positive");
        }
    }

    public static WorkflowLimits defaults() {
        return new WorkflowLimits(32, 256, 16);
    }
}

package io.haifa.agent.orchestration.api;

public record WorkflowLimits(
        int maximumIterationsPerNode,
        int maximumNodes,
        int maximumParallelBranches,
        int maximumSubgraphDepth,
        int maximumExpandedNodes,
        int maximumExpandedBranches) {
    public WorkflowLimits(int maximumIterationsPerNode, int maximumNodes, int maximumParallelBranches) {
        this(
                maximumIterationsPerNode,
                maximumNodes,
                maximumParallelBranches,
                4,
                Math.max(1024, maximumNodes),
                Math.max(64, maximumParallelBranches));
    }

    public WorkflowLimits(
            int maximumIterationsPerNode,
            int maximumNodes,
            int maximumParallelBranches,
            int maximumSubgraphDepth,
            int maximumExpandedNodes) {
        this(
                maximumIterationsPerNode,
                maximumNodes,
                maximumParallelBranches,
                maximumSubgraphDepth,
                maximumExpandedNodes,
                Math.max(64, maximumParallelBranches));
    }

    public WorkflowLimits {
        if (maximumIterationsPerNode < 1
                || maximumNodes < 1
                || maximumParallelBranches < 1
                || maximumSubgraphDepth < 1
                || maximumExpandedNodes < maximumNodes
                || maximumExpandedBranches < maximumParallelBranches) {
            throw new IllegalArgumentException("workflow limits must be positive");
        }
    }

    public static WorkflowLimits defaults() {
        return new WorkflowLimits(32, 256, 16, 4, 1024, 64);
    }
}

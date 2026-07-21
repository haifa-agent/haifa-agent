package io.haifa.agent.execution.core.manifest;

public record ManifestBudget(int maxFiles, long maxTotalBytes, long maxHashBytes) {
    public ManifestBudget {
        if (maxFiles < 1 || maxTotalBytes < 1 || maxHashBytes < 1)
            throw new IllegalArgumentException("budgets must be positive");
    }
}

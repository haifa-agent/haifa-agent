package io.haifa.agent.skill.core;

public record SkillPackageLimits(
        int maxFiles,
        int maxDepth,
        int maxFileBytes,
        int maxPackageBytes,
        int maxSkillBytes,
        int maxSkillLines,
        int maxEstimatedTokens) {
    public SkillPackageLimits {
        if (maxFiles < 1
                || maxDepth < 1
                || maxFileBytes < 1
                || maxPackageBytes < maxFileBytes
                || maxSkillBytes < 1
                || maxSkillLines < 1
                || maxEstimatedTokens < 1) {
            throw new IllegalArgumentException("skill package limits must be positive and internally consistent");
        }
    }

    public static SkillPackageLimits defaults() {
        return new SkillPackageLimits(128, 8, 512 * 1024, 2 * 1024 * 1024, 256 * 1024, 500, 5_000);
    }
}

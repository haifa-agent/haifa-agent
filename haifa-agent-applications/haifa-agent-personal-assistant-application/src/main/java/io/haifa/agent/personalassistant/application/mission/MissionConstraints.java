package io.haifa.agent.personalassistant.application.mission;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** User-visible limits narrowed by immutable product hard limits. */
public record MissionConstraints(int maxTasks, int maxDependencyDepth, Optional<Instant> deadlineAt) {
    public static final int HARD_MAX_TASKS = 16;
    public static final int HARD_MAX_DEPENDENCY_DEPTH = 8;
    public static final MissionConstraints DEFAULT = new MissionConstraints(8, 4, Optional.empty());

    public MissionConstraints {
        if (maxTasks < 1 || maxTasks > HARD_MAX_TASKS) {
            throw new MissionException("MISSION_LIMIT_EXCEEDED", "maxTasks must be between 1 and 16");
        }
        if (maxDependencyDepth < 1 || maxDependencyDepth > HARD_MAX_DEPENDENCY_DEPTH) {
            throw new MissionException("MISSION_LIMIT_EXCEEDED", "maxDependencyDepth must be between 1 and 8");
        }
        deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt must not be null")
                .map(value -> MissionValues.millisecond(value, "deadlineAt"));
    }
}

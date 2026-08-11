package io.haifa.agent.personalassistant.application.mission;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Immutable task definition belonging to one proposed or confirmed plan revision. */
public record MissionTask(
        String taskId,
        int ordinal,
        String title,
        String objective,
        List<String> acceptanceCriteria,
        List<String> dependsOn,
        String taskType,
        Set<String> requiredSkillIds,
        String resultSchemaId,
        String resultSchemaVersion,
        MissionTaskState state) {
    public MissionTask {
        taskId = MissionValues.text(taskId, "taskId", 64);
        if (!taskId.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new MissionException("MISSION_PLAN_INVALID", "taskId must be lower-case kebab-case");
        }
        if (ordinal < 1) throw new MissionException("MISSION_PLAN_INVALID", "task ordinal must be positive");
        title = MissionValues.text(title, "task title", 200);
        objective = MissionValues.text(objective, "task objective", 4_000);
        acceptanceCriteria = MissionValues.texts(acceptanceCriteria, "task acceptanceCriteria", 20, 1_000);
        dependsOn = List.copyOf(Objects.requireNonNull(dependsOn, "dependsOn must not be null"));
        dependsOn = dependsOn.stream()
                .map(value -> MissionValues.text(value, "dependency", 64))
                .toList();
        if (dependsOn.size() != dependsOn.stream().distinct().count()) {
            throw new MissionException("MISSION_PLAN_INVALID", "task dependencies must be unique");
        }
        taskType = MissionValues.text(taskType, "taskType", 32).toUpperCase(Locale.ROOT);
        if (!Set.of("GENERAL", "RESEARCH").contains(taskType)) {
            throw new MissionException("MISSION_PLAN_INVALID", "unsupported taskType");
        }
        requiredSkillIds = Set.copyOf(Objects.requireNonNull(requiredSkillIds, "requiredSkillIds must not be null"));
        requiredSkillIds.forEach(value -> MissionValues.text(value, "requiredSkillId", 128));
        resultSchemaId = MissionValues.text(resultSchemaId, "resultSchemaId", 128);
        resultSchemaVersion = MissionValues.text(resultSchemaVersion, "resultSchemaVersion", 32);
        state = Objects.requireNonNull(state, "state must not be null");
    }

    public MissionTask withState(MissionTaskState target) {
        return new MissionTask(
                taskId,
                ordinal,
                title,
                objective,
                acceptanceCriteria,
                dependsOn,
                taskType,
                requiredSkillIds,
                resultSchemaId,
                resultSchemaVersion,
                target);
    }
}

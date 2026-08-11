package io.haifa.agent.personalassistant.application.mission;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministic schema-independent business validation for an ordered DAG. */
public final class MissionPlanValidator {
    private final Set<String> allowedTaskTypes;
    private final Set<String> allowedSkillIds;
    private final Set<String> allowedResultSchemas;

    public MissionPlanValidator(
            Set<String> allowedTaskTypes, Set<String> allowedSkillIds, Set<String> allowedResultSchemas) {
        this.allowedTaskTypes = Set.copyOf(Objects.requireNonNull(allowedTaskTypes));
        this.allowedSkillIds = Set.copyOf(Objects.requireNonNull(allowedSkillIds));
        this.allowedResultSchemas = Set.copyOf(Objects.requireNonNull(allowedResultSchemas));
    }

    public static MissionPlanValidator phaseOne() {
        return new MissionPlanValidator(Set.of("GENERAL"), Set.of(), Set.of("pa.task-result@v1"));
    }

    public List<MissionTask> validate(List<MissionTask> proposed, MissionConstraints constraints) {
        List<MissionTask> tasks = List.copyOf(Objects.requireNonNull(proposed, "proposed must not be null"));
        if (tasks.isEmpty() || tasks.size() > constraints.maxTasks()) {
            throw new MissionException("MISSION_LIMIT_EXCEEDED", "plan task count is outside the configured limit");
        }
        Map<String, MissionTask> byId = new HashMap<>();
        Set<Integer> ordinals = new HashSet<>();
        for (MissionTask task : tasks) {
            if (byId.putIfAbsent(task.taskId(), task) != null) {
                throw new MissionException("MISSION_PLAN_INVALID", "task IDs must be unique");
            }
            if (!ordinals.add(task.ordinal())) {
                throw new MissionException("MISSION_PLAN_INVALID", "task ordinals must be unique");
            }
            if (!allowedTaskTypes.contains(task.taskType())) {
                throw new MissionException("MISSION_PLAN_ALLOWLIST_REJECTED", "task type is not allowed");
            }
            if (!allowedSkillIds.containsAll(task.requiredSkillIds())) {
                throw new MissionException("MISSION_PLAN_ALLOWLIST_REJECTED", "task Skill is not allowed");
            }
            if (!allowedResultSchemas.contains(task.resultSchemaId() + "@" + task.resultSchemaVersion())) {
                throw new MissionException("MISSION_PLAN_ALLOWLIST_REJECTED", "task result schema is not allowed");
            }
        }
        for (int ordinal = 1; ordinal <= tasks.size(); ordinal++) {
            if (!ordinals.contains(ordinal)) {
                throw new MissionException("MISSION_PLAN_INVALID", "task ordinals must be contiguous from one");
            }
        }
        for (MissionTask task : tasks) {
            for (String dependency : task.dependsOn()) {
                if (dependency.equals(task.taskId())) {
                    throw new MissionException("MISSION_PLAN_INVALID", "task cannot depend on itself");
                }
                MissionTask prerequisite = byId.get(dependency);
                if (prerequisite == null) {
                    throw new MissionException("MISSION_PLAN_INVALID", "task dependency does not exist");
                }
                if (prerequisite.ordinal() >= task.ordinal()) {
                    throw new MissionException("MISSION_PLAN_INVALID", "dependency must precede its dependent task");
                }
            }
        }
        Map<String, Integer> depths = new HashMap<>();
        ArrayDeque<String> visiting = new ArrayDeque<>();
        for (MissionTask task : tasks) {
            int depth = depth(task.taskId(), byId, depths, visiting);
            if (depth > constraints.maxDependencyDepth()) {
                throw new MissionException(
                        "MISSION_PLAN_DEPENDENCY_DEPTH_EXCEEDED", "plan dependency depth exceeds the limit");
            }
        }
        return tasks.stream()
                .sorted(java.util.Comparator.comparingInt(MissionTask::ordinal))
                .toList();
    }

    private static int depth(
            String taskId, Map<String, MissionTask> tasks, Map<String, Integer> memo, ArrayDeque<String> visiting) {
        Integer known = memo.get(taskId);
        if (known != null) return known;
        if (visiting.contains(taskId)) throw new MissionException("MISSION_PLAN_INVALID", "plan contains a cycle");
        visiting.push(taskId);
        int value = 1;
        for (String dependency : tasks.get(taskId).dependsOn()) {
            value = Math.max(value, 1 + depth(dependency, tasks, memo, visiting));
        }
        visiting.pop();
        memo.put(taskId, value);
        return value;
    }
}

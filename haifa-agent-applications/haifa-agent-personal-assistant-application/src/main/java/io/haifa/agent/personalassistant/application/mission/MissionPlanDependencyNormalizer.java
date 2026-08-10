package io.haifa.agent.personalassistant.application.mission;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministically removes the minimum serial edges needed to satisfy the frozen dependency-depth limit. */
public final class MissionPlanDependencyNormalizer {
    private MissionPlanDependencyNormalizer() {}

    public static List<MissionTask> flattenToMaximumDepth(List<MissionTask> proposed, int maximumDepth) {
        if (maximumDepth < 1) {
            throw new IllegalArgumentException("maximumDepth must be positive");
        }
        List<MissionTask> ordered = proposed.stream()
                .sorted(Comparator.comparingInt(MissionTask::ordinal))
                .toList();
        Map<String, MissionTask> tasks = new LinkedHashMap<>();
        ordered.forEach(task -> tasks.put(task.taskId(), task));

        while (true) {
            List<String> excessivePath = firstExcessivePath(tasks, maximumDepth);
            if (excessivePath.isEmpty()) return List.copyOf(tasks.values());

            int cutIndex = excessivePath.size() - maximumDepth;
            String predecessorId = excessivePath.get(cutIndex - 1);
            String dependentId = excessivePath.get(cutIndex);
            MissionTask dependent = Objects.requireNonNull(tasks.get(dependentId));
            List<String> normalizedDependencies = new ArrayList<>(dependent.dependsOn());
            if (!normalizedDependencies.remove(predecessorId)) {
                throw new IllegalStateException("Selected dependency edge is missing");
            }
            tasks.put(dependentId, withDependencies(dependent, normalizedDependencies));
        }
    }

    private static List<String> firstExcessivePath(Map<String, MissionTask> tasks, int maximumDepth) {
        Map<String, List<String>> memo = new HashMap<>();
        for (MissionTask task : tasks.values()) {
            List<String> path = deepestPath(task.taskId(), tasks, memo);
            if (path.size() > maximumDepth) return path;
        }
        return List.of();
    }

    private static List<String> deepestPath(
            String taskId, Map<String, MissionTask> tasks, Map<String, List<String>> memo) {
        List<String> known = memo.get(taskId);
        if (known != null) return known;
        MissionTask task = Objects.requireNonNull(tasks.get(taskId));
        List<String> deepest = List.of();
        for (String dependencyId : task.dependsOn()) {
            List<String> candidate = deepestPath(dependencyId, tasks, memo);
            if (candidate.size() > deepest.size()) deepest = candidate;
        }
        List<String> path = new ArrayList<>(deepest);
        path.add(taskId);
        List<String> result = List.copyOf(path);
        memo.put(taskId, result);
        return result;
    }

    private static MissionTask withDependencies(MissionTask task, List<String> dependencies) {
        return new MissionTask(
                task.taskId(),
                task.ordinal(),
                task.title(),
                task.objective(),
                task.acceptanceCriteria(),
                dependencies,
                task.taskType(),
                task.requiredSkillIds(),
                task.resultSchemaId(),
                task.resultSchemaVersion(),
                task.state());
    }
}

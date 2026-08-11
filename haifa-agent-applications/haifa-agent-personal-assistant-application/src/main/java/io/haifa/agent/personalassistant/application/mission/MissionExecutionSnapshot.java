package io.haifa.agent.personalassistant.application.mission;

import java.util.List;
import java.util.Optional;

/** Safe execution projection appended to the authoritative Mission Snapshot. */
public record MissionExecutionSnapshot(
        String dispatcherStatus,
        boolean recovering,
        boolean allTasksSettled,
        int completedTasks,
        int blockedTasks,
        Optional<String> currentTaskId,
        List<TaskExecution> tasks,
        Optional<MissionTaskAttempt> latestAttempt,
        List<String> artifacts,
        List<String> sources,
        Optional<String> finalResult) {

    public MissionExecutionSnapshot {
        tasks = List.copyOf(tasks);
        artifacts = List.copyOf(artifacts);
        sources = List.copyOf(sources);
    }

    public MissionExecutionSnapshot(
            String dispatcherStatus,
            boolean recovering,
            boolean allTasksSettled,
            int completedTasks,
            int blockedTasks,
            Optional<String> currentTaskId,
            List<TaskExecution> tasks,
            Optional<MissionTaskAttempt> latestAttempt) {
        this(
                dispatcherStatus,
                recovering,
                allTasksSettled,
                completedTasks,
                blockedTasks,
                currentTaskId,
                tasks,
                latestAttempt,
                List.of(),
                List.of(),
                Optional.empty());
    }

    public static MissionExecutionSnapshot unavailable() {
        return new MissionExecutionSnapshot(
                "NOT_CONFIGURED",
                false,
                false,
                0,
                0,
                Optional.empty(),
                List.of(),
                Optional.empty(),
                List.of(),
                List.of(),
                Optional.empty());
    }

    public record TaskExecution(
            String taskId,
            int ordinal,
            MissionTaskState state,
            int latestAttemptNo,
            Optional<String> runId,
            Optional<String> resultDigest,
            Optional<String> blockCode) {}
}

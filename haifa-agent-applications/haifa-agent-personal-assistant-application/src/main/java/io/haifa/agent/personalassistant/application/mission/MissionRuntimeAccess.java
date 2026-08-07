package io.haifa.agent.personalassistant.application.mission;

/** Minimal standard-Run boundary used by the Server's strict structured plan decoder. */
public interface MissionRuntimeAccess {
    PlannerRunResult runPlanner(MissionPlanner.PlanningRequest request);

    default TaskRunBinding startTask(MissionDispatchIntent intent) {
        throw new MissionException("MISSION_EXECUTION_UNAVAILABLE", "Mission Task execution is unavailable");
    }

    default TaskRunObservation observeTask(String runId) {
        return new TaskRunObservation(
                runId,
                TaskRunState.OUTCOME_UNKNOWN,
                java.util.Optional.empty(),
                java.util.Optional.of("MISSION_EXECUTION_UNAVAILABLE"));
    }

    default void cancelTask(String runId) {}

    record PlannerRunResult(String sessionId, String runId, String structuredOutput) {}

    record TaskRunBinding(String sessionId, String runId) {}

    record TaskRunObservation(
            String runId,
            TaskRunState state,
            java.util.Optional<String> result,
            java.util.Optional<String> failureCode) {}

    enum TaskRunState {
        ACTIVE,
        WAITING_USER,
        COMPLETED,
        FAILED,
        CANCELLED,
        OUTCOME_UNKNOWN
    }
}

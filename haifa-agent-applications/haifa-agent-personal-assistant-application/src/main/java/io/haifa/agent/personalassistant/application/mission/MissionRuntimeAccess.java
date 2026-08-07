package io.haifa.agent.personalassistant.application.mission;

/** Minimal standard-Run boundary used by the Server's strict structured plan decoder. */
public interface MissionRuntimeAccess {
    PlannerRunResult runPlanner(MissionPlanner.PlanningRequest request);

    record PlannerRunResult(String sessionId, String runId, String structuredOutput) {}
}

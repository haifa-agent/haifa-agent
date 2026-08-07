package io.haifa.agent.personalassistant.application.mission;

import java.util.List;
import java.util.Optional;

/** Planner port. Production and deterministic adapters must both return validated structured tasks. */
public interface MissionPlanner {
    PlanningResult plan(PlanningRequest request);

    record PlanningRequest(
            String missionId,
            String objective,
            List<String> acceptanceCriteria,
            MissionConstraints constraints,
            int revisionNo) {
        public PlanningRequest {
            acceptanceCriteria = List.copyOf(acceptanceCriteria);
        }
    }

    record PlanningResult(
            String schemaId,
            String schemaVersion,
            List<MissionTask> tasks,
            Optional<String> plannerSessionId,
            Optional<String> plannerRunId) {
        public PlanningResult {
            tasks = List.copyOf(tasks);
        }
    }
}

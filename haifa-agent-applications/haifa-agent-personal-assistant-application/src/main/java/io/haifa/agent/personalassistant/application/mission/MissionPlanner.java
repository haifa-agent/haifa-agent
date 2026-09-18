package io.haifa.agent.personalassistant.application.mission;

import java.util.List;
import java.util.Optional;

/** Planner port. Production and deterministic adapters must both return validated structured tasks. */
public interface MissionPlanner {
    PlanningResult plan(PlanningRequest request);

    record PlanningRequest(
            String missionId,
            MissionModelBinding modelBinding,
            String objective,
            List<String> acceptanceCriteria,
            MissionConstraints constraints,
            int revisionNo,
            MissionMode mode,
            Optional<ResearchBrief> researchBrief) {
        public PlanningRequest {
            modelBinding = java.util.Objects.requireNonNull(modelBinding);
            acceptanceCriteria = List.copyOf(acceptanceCriteria);
            mode = java.util.Objects.requireNonNull(mode);
            researchBrief = java.util.Objects.requireNonNull(researchBrief);
            if (mode == MissionMode.DEEP_RESEARCH && researchBrief.isEmpty()) {
                throw new MissionException("MISSION_RESEARCH_BRIEF_REQUIRED", "Deep Research requires a brief");
            }
            if (mode == MissionMode.STANDARD && researchBrief.isPresent()) {
                throw new MissionException("MISSION_RESEARCH_BRIEF_FORBIDDEN", "Standard Mission cannot carry a brief");
            }
        }

        public PlanningRequest(
                String missionId,
                String objective,
                List<String> acceptanceCriteria,
                MissionConstraints constraints,
                int revisionNo,
                MissionMode mode,
                Optional<ResearchBrief> researchBrief) {
            this(
                    missionId,
                    MissionModelBinding.legacyDefault(),
                    objective,
                    acceptanceCriteria,
                    constraints,
                    revisionNo,
                    mode,
                    researchBrief);
        }

        public PlanningRequest(
                String missionId,
                String objective,
                List<String> acceptanceCriteria,
                MissionConstraints constraints,
                int revisionNo) {
            this(
                    missionId,
                    MissionModelBinding.legacyDefault(),
                    objective,
                    acceptanceCriteria,
                    constraints,
                    revisionNo,
                    MissionMode.STANDARD,
                    Optional.empty());
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

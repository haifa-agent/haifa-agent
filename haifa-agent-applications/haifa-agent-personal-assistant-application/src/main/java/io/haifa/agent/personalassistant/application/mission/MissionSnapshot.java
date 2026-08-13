package io.haifa.agent.personalassistant.application.mission;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Product query model; adapters map it to versioned wire DTOs. */
public record MissionSnapshot(
        String schemaVersion,
        String missionId,
        String conversationId,
        MissionModelBinding modelBinding,
        String objective,
        List<String> acceptanceCriteria,
        MissionConstraints constraints,
        MissionMode mode,
        Optional<ResearchBrief> researchBrief,
        Optional<String> selectedSkillId,
        Optional<String> selectedSkillBinding,
        MissionState state,
        Optional<MissionPlanRevision> plan,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Optional<Instant> confirmedAt,
        Optional<Instant> finishedAt,
        Optional<String> failureCode,
        long pollAfterMillis,
        MissionExecutionSnapshot execution) {
    public static final String SCHEMA_VERSION = "pa.mission-snapshot/v2";

    static MissionSnapshot from(PersonalMission mission) {
        long poll =
                switch (mission.state()) {
                    case PLANNING, RUNNING, SYNTHESIZING -> 2_000;
                    case WAITING_CONFIRMATION, WAITING_USER -> 5_000;
                    default -> 0;
                };
        return new MissionSnapshot(
                SCHEMA_VERSION,
                mission.missionId(),
                mission.conversationId(),
                mission.modelBinding(),
                mission.objective(),
                mission.acceptanceCriteria(),
                mission.constraints(),
                mission.mode(),
                mission.researchBrief(),
                mission.selectedSkillId(),
                mission.selectedSkillBinding(),
                mission.state(),
                mission.activePlan(),
                mission.version(),
                mission.createdAt(),
                mission.updatedAt(),
                mission.confirmedAt(),
                mission.finishedAt(),
                mission.failureCode(),
                poll,
                MissionExecutionSnapshot.unavailable());
    }

    public MissionSnapshot withExecution(MissionExecutionSnapshot value) {
        return new MissionSnapshot(
                schemaVersion,
                missionId,
                conversationId,
                modelBinding,
                objective,
                acceptanceCriteria,
                constraints,
                mode,
                researchBrief,
                selectedSkillId,
                selectedSkillBinding,
                state,
                plan,
                version,
                createdAt,
                updatedAt,
                confirmedAt,
                finishedAt,
                failureCode,
                pollAfterMillis,
                value);
    }
}

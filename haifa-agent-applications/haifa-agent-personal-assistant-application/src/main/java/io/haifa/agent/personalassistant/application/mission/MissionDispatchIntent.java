package io.haifa.agent.personalassistant.application.mission;

import java.time.Instant;
import java.util.List;

/** Claimed product Outbox intent carrying only the frozen Task execution request. */
public record MissionDispatchIntent(
        String outboxId,
        String missionId,
        String ownerScope,
        String taskId,
        int attemptNo,
        String dispatchKey,
        String payloadDigest,
        String objective,
        List<String> acceptanceCriteria,
        String taskType,
        List<String> requiredSkillIds,
        String resultSchemaId,
        String resultSchemaVersion,
        Instant claimedAt) {
    public MissionDispatchIntent {
        acceptanceCriteria = List.copyOf(acceptanceCriteria);
        requiredSkillIds = List.copyOf(requiredSkillIds);
    }

    public MissionDispatchIntent(
            String outboxId,
            String missionId,
            String ownerScope,
            String taskId,
            int attemptNo,
            String dispatchKey,
            String payloadDigest,
            String objective,
            List<String> acceptanceCriteria,
            String resultSchemaId,
            String resultSchemaVersion,
            Instant claimedAt) {
        this(
                outboxId,
                missionId,
                ownerScope,
                taskId,
                attemptNo,
                dispatchKey,
                payloadDigest,
                objective,
                acceptanceCriteria,
                "GENERAL",
                List.of(),
                resultSchemaId,
                resultSchemaVersion,
                claimedAt);
    }
}

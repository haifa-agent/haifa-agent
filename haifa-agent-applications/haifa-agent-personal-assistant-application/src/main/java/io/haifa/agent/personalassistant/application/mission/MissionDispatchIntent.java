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
        String resultSchemaId,
        String resultSchemaVersion,
        Instant claimedAt) {
    public MissionDispatchIntent {
        acceptanceCriteria = List.copyOf(acceptanceCriteria);
    }
}

package io.haifa.agent.personalassistant.application.mission;

import java.time.Instant;

/** Durable product command reservation used for exactly-once command results. */
public record MissionCommandBinding(
        String ownerScope,
        String operation,
        String idempotencyKey,
        String requestDigest,
        String missionId,
        Instant createdAt) {
    public MissionCommandBinding {
        ownerScope = MissionValues.text(ownerScope, "ownerScope", 256);
        operation = MissionValues.text(operation, "operation", 64);
        idempotencyKey = MissionValues.text(idempotencyKey, "idempotencyKey", 128);
        requestDigest = MissionValues.text(requestDigest, "requestDigest", 80);
        missionId = MissionValues.text(missionId, "missionId", 256);
        createdAt = MissionValues.millisecond(createdAt, "createdAt");
    }
}

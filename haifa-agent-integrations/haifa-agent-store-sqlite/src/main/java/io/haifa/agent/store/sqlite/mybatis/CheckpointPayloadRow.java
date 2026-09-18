package io.haifa.agent.store.sqlite.mybatis;

import java.time.Instant;

public record CheckpointPayloadRow(
        String checkpointId,
        String stateSchemaVersion,
        byte[] statePayload,
        String stateHash,
        String payloadHash,
        Instant createdAt) {}

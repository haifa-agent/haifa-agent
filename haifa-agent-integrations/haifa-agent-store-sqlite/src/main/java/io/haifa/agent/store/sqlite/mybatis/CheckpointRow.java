package io.haifa.agent.store.sqlite.mybatis;

import java.time.Instant;

public record CheckpointRow(
        String checkpointId,
        String runId,
        String stepId,
        String type,
        String status,
        long sequence,
        String payloadStoreType,
        String payloadLocation,
        String payloadSchemaId,
        String payloadSchemaVersion,
        String stateHash,
        Instant createdAt) {}

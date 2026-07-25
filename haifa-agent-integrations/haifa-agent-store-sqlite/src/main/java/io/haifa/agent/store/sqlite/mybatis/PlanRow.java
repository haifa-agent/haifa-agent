package io.haifa.agent.store.sqlite.mybatis;

import java.time.Instant;

public record PlanRow(
        String planId,
        String schemaVersion,
        String runId,
        String objective,
        String itemsSchemaVersion,
        byte[] itemsPayload,
        String itemsHash,
        long revision,
        Instant createdAt,
        Instant updatedAt) {}

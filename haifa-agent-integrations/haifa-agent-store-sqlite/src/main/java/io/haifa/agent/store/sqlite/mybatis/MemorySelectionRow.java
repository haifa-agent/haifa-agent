package io.haifa.agent.store.sqlite.mybatis;

import java.time.Instant;

public record MemorySelectionRow(
        String runId,
        String retrievalPolicyVersion,
        String queryDigest,
        String memoriesSchemaVersion,
        byte[] memoriesPayload,
        String memoriesHash,
        Instant updatedAt) {}

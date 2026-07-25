package io.haifa.agent.store.sqlite.mybatis;

import java.time.Instant;

public record RunOutputRow(
        String runId, String outputSchemaVersion, byte[] outputPayload, String outputHash, Instant updatedAt) {}

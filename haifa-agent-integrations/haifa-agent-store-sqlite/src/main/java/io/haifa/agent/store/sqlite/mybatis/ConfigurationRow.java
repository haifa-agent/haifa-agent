package io.haifa.agent.store.sqlite.mybatis;

import java.time.Instant;

public record ConfigurationRow(
        String configurationRef,
        String schemaVersion,
        String definitionId,
        String definitionVersion,
        String profileId,
        String profileVersion,
        String runType,
        String contentSchemaVersion,
        byte[] contentPayload,
        String contentHash,
        String contentPayloadHash,
        Instant createdAt) {}

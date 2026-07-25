package io.haifa.agent.store.sqlite.mybatis;

import java.time.Instant;

public record SkillActivationRow(
        String runId,
        String skillAlias,
        String coordinate,
        String contentDigest,
        String reason,
        String requestedBy,
        int instructionBytes,
        int estimatedTokens,
        String activationSchemaVersion,
        byte[] activationPayload,
        String activationHash,
        Instant activatedAt) {}

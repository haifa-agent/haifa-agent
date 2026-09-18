package io.haifa.agent.store.sqlite.mybatis;

import java.time.Instant;

public record ToolResultAssetRow(
        String assetRef,
        String toolCallId,
        String resultSchemaVersion,
        byte[] resultPayload,
        String resultHash,
        int byteLength,
        Instant createdAt) {}

package io.haifa.agent.store.sqlite.mybatis;

import java.time.Instant;

public record PolicySnapshotRow(
        String snapshotId,
        String tenantId,
        String productId,
        String approvalMode,
        String productProfileRef,
        String projectTrustRef,
        String rulesSchemaVersion,
        byte[] rulesPayload,
        String rulesHash,
        String contentDigest,
        Instant createdAt) {}

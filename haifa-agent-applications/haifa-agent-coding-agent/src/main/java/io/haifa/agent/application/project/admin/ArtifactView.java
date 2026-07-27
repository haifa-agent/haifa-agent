package io.haifa.agent.application.project.admin;

import java.time.Instant;

public record ArtifactView(
        String artifactId,
        long version,
        String type,
        String title,
        String status,
        String payloadDigest,
        long byteCount,
        String workspaceRef,
        String runRef,
        Instant createdAt) {}

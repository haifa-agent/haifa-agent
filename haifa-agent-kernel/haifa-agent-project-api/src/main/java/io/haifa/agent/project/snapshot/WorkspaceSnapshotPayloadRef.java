package io.haifa.agent.project.snapshot;

import java.util.Objects;

public record WorkspaceSnapshotPayloadRef(String payloadId, String sha256, long byteCount) {
    public WorkspaceSnapshotPayloadRef {
        payloadId =
                Objects.requireNonNull(payloadId, "payloadId must not be null").trim();
        sha256 = Objects.requireNonNull(sha256, "sha256 must not be null").trim();
        if (payloadId.isEmpty() || !sha256.startsWith("sha256:") || byteCount < 0) {
            throw new IllegalArgumentException("invalid snapshot payload reference");
        }
    }
}

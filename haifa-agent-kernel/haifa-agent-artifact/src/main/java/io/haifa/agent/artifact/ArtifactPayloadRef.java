package io.haifa.agent.artifact;

import java.util.Objects;

public record ArtifactPayloadRef(String payloadId, String sha256, long byteCount, String mediaType) {
    public ArtifactPayloadRef {
        payloadId = require(payloadId, "payloadId");
        sha256 = require(sha256, "sha256");
        if (!sha256.startsWith("sha256:") || byteCount < 0)
            throw new IllegalArgumentException("invalid payload metadata");
        mediaType = require(mediaType, "mediaType");
    }

    private static String require(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}

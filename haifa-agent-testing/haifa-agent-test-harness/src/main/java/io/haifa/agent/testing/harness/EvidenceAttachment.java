package io.haifa.agent.testing.harness;

import java.util.Objects;

/** Digest-bound relative attachment referenced by the authoritative run result. */
public record EvidenceAttachment(String type, String relativePath, long size, String sha256) {
    public EvidenceAttachment {
        type = require(type, "type");
        relativePath = require(relativePath, "relativePath").replace('\\', '/');
        if (relativePath.startsWith("/") || relativePath.contains("../")) {
            throw new IllegalArgumentException("attachment path must be relative and contained");
        }
        if (size < 0) throw new IllegalArgumentException("attachment size must not be negative");
        sha256 = require(sha256, "sha256");
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("attachment sha256 must be lowercase SHA-256");
        }
    }

    private static String require(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}

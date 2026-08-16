package io.haifa.agent.testing.fixtures;

import java.util.Objects;

/** Self-contained fixture package metadata. */
public record FixturePackageManifest(
        int schemaVersion,
        String id,
        int version,
        String workspace,
        String acceptance,
        String license,
        String contentSha256) {
    public FixturePackageManifest {
        if (schemaVersion != 1) throw new IllegalArgumentException("fixture package schemaVersion must be 1");
        new FixtureReference(id, version);
        workspace = relative(workspace, "workspace");
        acceptance = relative(acceptance, "acceptance");
        license = require(license, "license");
        contentSha256 = require(contentSha256, "contentSha256");
        if (!contentSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("fixture package contentSha256 must be lowercase SHA-256");
        }
    }

    private static String relative(String value, String field) {
        String normalized = require(value, field).replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("../")) {
            throw new IllegalArgumentException(field + " must be a contained relative path");
        }
        return normalized;
    }

    private static String require(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}

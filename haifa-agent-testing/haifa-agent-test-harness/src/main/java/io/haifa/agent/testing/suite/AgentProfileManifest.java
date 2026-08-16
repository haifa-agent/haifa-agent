package io.haifa.agent.testing.suite;

import java.util.Objects;

/** Provider-neutral reference to one reviewed standalone Coding Agent configuration. */
public record AgentProfileManifest(
        int schemaVersion,
        String profileId,
        String compatibleAgentBaselineCommit,
        String configurationRef,
        String configurationSha256) {
    public AgentProfileManifest {
        if (schemaVersion != 1) throw new IllegalArgumentException("agent profile schemaVersion must be 1");
        profileId = identifier(profileId, "profileId");
        compatibleAgentBaselineCommit = require(compatibleAgentBaselineCommit, "compatibleAgentBaselineCommit");
        if (!compatibleAgentBaselineCommit.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("compatibleAgentBaselineCommit must be a full lowercase Git commit");
        }
        configurationRef = safeRelativePath(configurationRef, "configurationRef");
        configurationSha256 = require(configurationSha256, "configurationSha256");
        if (!configurationSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("configurationSha256 must be lowercase SHA-256");
        }
    }

    private static String safeRelativePath(String value, String field) {
        String path = require(value, field);
        if (path.startsWith("/") || path.contains("\\") || path.contains("..") || path.contains("//")) {
            throw new IllegalArgumentException(field + " must be a safe config-root-relative path");
        }
        return path;
    }

    private static String identifier(String value, String field) {
        String id = require(value, field);
        if (!id.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException(field + " must be lowercase kebab-case");
        }
        return id;
    }

    private static String require(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}

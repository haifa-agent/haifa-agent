package io.haifa.agent.runtime.core.recovery;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Safe canonical identity for semantically equivalent Tool failures. */
public record FailureFingerprint(
        String toolCoordinateDigest,
        String commandTarget,
        String effectiveOperation,
        ToolFailureCategory failureCategory,
        String stableFailureCode,
        String normalizedIntentDigest,
        String resourceClass,
        String sandboxProfileDigest,
        String digest) {
    public FailureFingerprint(
            String toolCoordinateDigest,
            String commandTarget,
            String effectiveOperation,
            ToolFailureCategory failureCategory,
            String stableFailureCode,
            String normalizedIntentDigest,
            String resourceClass,
            String sandboxProfileDigest) {
        this(
                safe(toolCoordinateDigest, "toolCoordinateDigest"),
                token(commandTarget, "commandTarget"),
                token(effectiveOperation, "effectiveOperation"),
                Objects.requireNonNull(failureCategory, "failureCategory must not be null"),
                token(stableFailureCode, "stableFailureCode"),
                safe(normalizedIntentDigest, "normalizedIntentDigest"),
                token(resourceClass, "resourceClass"),
                safe(sandboxProfileDigest, "sandboxProfileDigest"),
                digest(List.of(
                        safe(toolCoordinateDigest, "toolCoordinateDigest"),
                        token(commandTarget, "commandTarget"),
                        token(effectiveOperation, "effectiveOperation"),
                        failureCategory.name(),
                        token(stableFailureCode, "stableFailureCode"),
                        safe(normalizedIntentDigest, "normalizedIntentDigest"),
                        token(resourceClass, "resourceClass"),
                        safe(sandboxProfileDigest, "sandboxProfileDigest"))));
    }

    /** Source-compatible constructor for non-command callers that predate trusted target and intent fields. */
    public FailureFingerprint(
            String toolCoordinateDigest,
            String operationFamily,
            ToolFailureCategory failureCategory,
            String stableFailureCode,
            String resourceClass,
            String sandboxProfileDigest) {
        this(
                toolCoordinateDigest,
                "OTHER",
                operationFamily,
                failureCategory,
                stableFailureCode,
                digest(List.of("legacy-intent")),
                resourceClass,
                sandboxProfileDigest);
    }

    public FailureFingerprint {
        if (!digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("failure fingerprint digest must be lowercase SHA-256");
        }
    }

    public static String digest(List<String> values) {
        StringBuilder canonical = new StringBuilder();
        values.forEach(value ->
                canonical.append(value.length()).append(':').append(value).append(';'));
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String safe(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (!normalized.matches("(?:sha256:)?[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a SHA-256 digest");
        }
        return normalized;
    }

    private static String token(String value, String field) {
        String normalized = Objects.requireNonNull(value, field + " must not be null")
                .trim()
                .toUpperCase(java.util.Locale.ROOT);
        if (!normalized.matches("[A-Z0-9][A-Z0-9_.:-]{0,127}")) {
            throw new IllegalArgumentException(field + " must be a stable token");
        }
        return normalized;
    }
}

package io.haifa.agent.application.project.product.coding.delivery;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** One immutable validation attempt; runner output is never a trusted count or scope source. */
public record CodingValidationAttemptEvidence(
        String schemaVersion,
        CodingValidationScope scope,
        String verificationSource,
        String claimCode,
        String verificationProfileDigest,
        String verificationCandidateDigest) {
    public static final String SCHEMA_VERSION = "coding-validation-attempt/3";

    public CodingValidationAttemptEvidence {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported validation evidence schemaVersion");
        }
        scope = Objects.requireNonNull(scope, "scope must not be null");
        verificationSource = token(verificationSource, "verificationSource");
        claimCode = token(claimCode, "claimCode");
        verificationProfileDigest = digest(verificationProfileDigest, "verificationProfileDigest");
        verificationCandidateDigest = digest(verificationCandidateDigest, "verificationCandidateDigest");
    }

    public Map<String, Object> toStructuredData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemaVersion", schemaVersion);
        data.put("scope", scope.name());
        data.put("verificationSource", verificationSource);
        data.put("claimCode", claimCode);
        data.put("verificationProfileDigest", verificationProfileDigest);
        data.put("verificationCandidateDigest", verificationCandidateDigest);
        return Map.copyOf(data);
    }

    public static Optional<CodingValidationAttemptEvidence> fromStructuredData(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Optional.empty();
        try {
            String schemaVersion = text(map, "schemaVersion");
            return Optional.of(new CodingValidationAttemptEvidence(
                    schemaVersion,
                    CodingValidationScope.valueOf(text(map, "scope")),
                    text(map, "verificationSource"),
                    text(map, "claimCode"),
                    text(map, "verificationProfileDigest"),
                    text(map, "verificationCandidateDigest")));
        } catch (IllegalArgumentException | ClassCastException ignored) {
            return Optional.empty();
        }
    }

    private static String text(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException(key + " is invalid");
        return text;
    }

    private static String token(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (!normalized.matches("[A-Z][A-Z0-9_]{0,63}")) throw new IllegalArgumentException(field + " is invalid");
        return normalized;
    }

    private static String digest(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (!normalized.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
}

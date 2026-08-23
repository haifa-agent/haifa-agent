package io.haifa.agent.application.project.product.coding.delivery;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** One immutable validation attempt; runner output is never a trusted count or scope source. */
public record CodingValidationAttemptEvidence(
        String schemaVersion,
        CodingValidationStatus status,
        Integer discoveredTestCount,
        Integer selectedTestCount,
        Integer ignoredTestCount,
        CodingValidationScope scope,
        String countSource,
        String verificationSource,
        String claimCode,
        String verificationProfileDigest,
        String verificationCandidateDigest) {
    public static final String SCHEMA_VERSION = "coding-validation-evidence/2";
    private static final String LEGACY_SCHEMA_VERSION = "coding-validation-evidence/1";

    public CodingValidationAttemptEvidence {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported validation evidence schemaVersion");
        }
        status = Objects.requireNonNull(status, "status must not be null");
        scope = Objects.requireNonNull(scope, "scope must not be null");
        countSource = token(countSource, "countSource");
        verificationSource = token(verificationSource, "verificationSource");
        claimCode = token(claimCode, "claimCode");
        verificationProfileDigest = digest(verificationProfileDigest, "verificationProfileDigest");
        verificationCandidateDigest = digest(verificationCandidateDigest, "verificationCandidateDigest");
        boolean none = discoveredTestCount == null && selectedTestCount == null && ignoredTestCount == null;
        boolean all = discoveredTestCount != null && selectedTestCount != null && ignoredTestCount != null;
        if (!none && !all)
            throw new IllegalArgumentException("validation test counts must be all present or all absent");
        if (all) {
            if (discoveredTestCount < 0 || selectedTestCount < 0 || ignoredTestCount < 0) {
                throw new IllegalArgumentException("validation test counts must not be negative");
            }
            if (selectedTestCount > discoveredTestCount || ignoredTestCount > discoveredTestCount) {
                throw new IllegalArgumentException("validation test counts are inconsistent");
            }
            if ("COUNTS_UNAVAILABLE".equals(countSource)) {
                throw new IllegalArgumentException("authoritative counts require an authoritative source");
            }
        } else if (!"COUNTS_UNAVAILABLE".equals(countSource)) {
            throw new IllegalArgumentException("missing counts require COUNTS_UNAVAILABLE");
        }
    }

    public Map<String, Object> toStructuredData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemaVersion", schemaVersion);
        data.put("status", status.name());
        if (selectedTestCount != null) {
            data.put("discoveredTestCount", discoveredTestCount);
            data.put("selectedTestCount", selectedTestCount);
            data.put("ignoredTestCount", ignoredTestCount);
        }
        data.put("scope", scope.name());
        data.put("countSource", countSource);
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
            if (LEGACY_SCHEMA_VERSION.equals(schemaVersion)) return Optional.of(fromLegacy(map));
            return Optional.of(new CodingValidationAttemptEvidence(
                    schemaVersion,
                    CodingValidationStatus.valueOf(text(map, "status")),
                    integer(map, "discoveredTestCount"),
                    integer(map, "selectedTestCount"),
                    integer(map, "ignoredTestCount"),
                    CodingValidationScope.valueOf(text(map, "scope")),
                    text(map, "countSource"),
                    text(map, "verificationSource"),
                    text(map, "claimCode"),
                    text(map, "verificationProfileDigest"),
                    text(map, "verificationCandidateDigest")));
        } catch (IllegalArgumentException | ClassCastException ignored) {
            return Optional.empty();
        }
    }

    public static CodingValidationAttemptEvidence unavailable(CodingValidationStatus status, String source) {
        return new CodingValidationAttemptEvidence(
                SCHEMA_VERSION,
                status,
                null,
                null,
                null,
                CodingValidationScope.UNKNOWN,
                "COUNTS_UNAVAILABLE",
                source,
                "SCOPE_UNAVAILABLE",
                "UNAVAILABLE",
                "UNMATCHED");
    }

    private static CodingValidationAttemptEvidence fromLegacy(Map<?, ?> map) {
        return new CodingValidationAttemptEvidence(
                SCHEMA_VERSION,
                CodingValidationStatus.valueOf(text(map, "status")),
                null,
                null,
                null,
                CodingValidationScope.UNKNOWN,
                "COUNTS_UNAVAILABLE",
                "LEGACY_TOOL_RESULT",
                "LEGACY_COUNTS_UNTRUSTED",
                "UNAVAILABLE",
                "UNMATCHED");
    }

    private static Integer integer(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (!(value instanceof Number number)) throw new IllegalArgumentException(key + " is invalid");
        return number.intValue();
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
        if (!normalized.matches("[a-f0-9]{64}|UNAVAILABLE|UNMATCHED")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
}

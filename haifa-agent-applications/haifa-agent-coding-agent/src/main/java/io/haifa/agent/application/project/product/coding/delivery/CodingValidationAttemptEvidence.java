package io.haifa.agent.application.project.product.coding.delivery;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** One immutable validation attempt; counts are nullable when a tool cannot report them reliably. */
public record CodingValidationAttemptEvidence(
        String schemaVersion,
        CodingValidationStatus status,
        Integer discoveredTestCount,
        Integer selectedTestCount,
        Integer ignoredTestCount,
        CodingValidationScope scope,
        String countSource,
        String claimCode) {
    public static final String SCHEMA_VERSION = "coding-validation-evidence/1";

    public CodingValidationAttemptEvidence {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported validation evidence schemaVersion");
        }
        status = Objects.requireNonNull(status, "status must not be null");
        scope = Objects.requireNonNull(scope, "scope must not be null");
        countSource = token(countSource, "countSource");
        claimCode = token(claimCode, "claimCode");
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
            if (scope == CodingValidationScope.UNKNOWN) {
                throw new IllegalArgumentException("known validation counts require a known scope");
            }
        } else if (scope != CodingValidationScope.UNKNOWN) {
            throw new IllegalArgumentException("unknown validation counts require UNKNOWN scope");
        }
    }

    public Map<String, Object> toStructuredData() {
        if (selectedTestCount == null) {
            return Map.of(
                    "schemaVersion", schemaVersion,
                    "status", status.name(),
                    "scope", scope.name(),
                    "countSource", countSource,
                    "claimCode", claimCode);
        }
        return Map.ofEntries(
                Map.entry("schemaVersion", schemaVersion),
                Map.entry("status", status.name()),
                Map.entry("discoveredTestCount", discoveredTestCount),
                Map.entry("selectedTestCount", selectedTestCount),
                Map.entry("ignoredTestCount", ignoredTestCount),
                Map.entry("scope", scope.name()),
                Map.entry("countSource", countSource),
                Map.entry("claimCode", claimCode));
    }

    public static Optional<CodingValidationAttemptEvidence> fromStructuredData(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Optional.empty();
        try {
            return Optional.of(new CodingValidationAttemptEvidence(
                    text(map, "schemaVersion"),
                    CodingValidationStatus.valueOf(text(map, "status")),
                    integer(map, "discoveredTestCount"),
                    integer(map, "selectedTestCount"),
                    integer(map, "ignoredTestCount"),
                    CodingValidationScope.valueOf(text(map, "scope")),
                    text(map, "countSource"),
                    text(map, "claimCode")));
        } catch (IllegalArgumentException | ClassCastException ignored) {
            return Optional.empty();
        }
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
}

package io.haifa.agent.application.project.product.coding.delivery;

import io.haifa.agent.project.changeset.FileChangeType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Content-addressed, body-free review document derived from authoritative FileChangeSets. */
public record CodingChangeReviewArtifact(
        String schemaVersion,
        String artifactRef,
        List<String> changeSetIds,
        String baseWorkspaceDigest,
        String resultWorkspaceDigest,
        List<FileSummary> fileSummaries,
        int totalFileCount,
        boolean summariesTruncated,
        Map<String, Integer> counts,
        AttributionStatus attributionStatus) {
    public static final String SCHEMA_VERSION = "coding-change-review/2";
    public static final String LEGACY_SCHEMA_VERSION = "coding-change-review/1";
    public static final int MAXIMUM_CHANGE_SET_IDS = 128;
    public static final int MAXIMUM_FILE_SUMMARIES = 128;
    private static final List<String> COUNT_KEYS =
            List.of("created", "replaced", "deleted", "moved", "binary", "oversize", "opaque");

    public CodingChangeReviewArtifact {
        if (!SCHEMA_VERSION.equals(schemaVersion) && !LEGACY_SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported change review schemaVersion");
        }
        artifactRef = digest(artifactRef, "artifactRef");
        changeSetIds = strings(changeSetIds, "changeSetIds", MAXIMUM_CHANGE_SET_IDS, 256);
        if (changeSetIds.isEmpty()) throw new IllegalArgumentException("changeSetIds must not be empty");
        baseWorkspaceDigest = digest(baseWorkspaceDigest, "baseWorkspaceDigest");
        resultWorkspaceDigest = digest(resultWorkspaceDigest, "resultWorkspaceDigest");
        fileSummaries = List.copyOf(Objects.requireNonNull(fileSummaries, "fileSummaries must not be null"));
        if (fileSummaries.size() > MAXIMUM_FILE_SUMMARIES) {
            throw new IllegalArgumentException("fileSummaries exceeds its bound");
        }
        if (totalFileCount < fileSummaries.size()) {
            throw new IllegalArgumentException("totalFileCount is smaller than fileSummaries");
        }
        if (summariesTruncated != (totalFileCount > fileSummaries.size())) {
            throw new IllegalArgumentException("summariesTruncated does not match totalFileCount");
        }
        Map<String, Integer> normalized = new LinkedHashMap<>();
        Map<String, Integer> supplied = Objects.requireNonNull(counts, "counts must not be null");
        for (String key : COUNT_KEYS) {
            Integer value = supplied.get(key);
            if (value == null || value < 0) throw new IllegalArgumentException("invalid change review count: " + key);
            normalized.put(key, value);
        }
        if (supplied.size() != COUNT_KEYS.size()) {
            throw new IllegalArgumentException("change review counts contain unknown keys");
        }
        counts = Map.copyOf(normalized);
        attributionStatus = Objects.requireNonNull(attributionStatus, "attributionStatus must not be null");
        String expectedRef = contentAddress(
                schemaVersion,
                changeSetIds,
                baseWorkspaceDigest,
                resultWorkspaceDigest,
                fileSummaries,
                totalFileCount,
                summariesTruncated,
                counts,
                attributionStatus);
        if (!artifactRef.equals(expectedRef)) {
            throw new IllegalArgumentException("artifactRef does not match change review content");
        }
    }

    public static CodingChangeReviewArtifact create(
            List<String> changeSetIds,
            String baseWorkspaceDigest,
            String resultWorkspaceDigest,
            List<FileSummary> fileSummaries,
            int totalFileCount,
            boolean summariesTruncated,
            Map<String, Integer> counts,
            AttributionStatus attributionStatus) {
        String artifactRef = contentAddress(
                SCHEMA_VERSION,
                changeSetIds,
                baseWorkspaceDigest,
                resultWorkspaceDigest,
                fileSummaries,
                totalFileCount,
                summariesTruncated,
                counts,
                attributionStatus);
        return new CodingChangeReviewArtifact(
                SCHEMA_VERSION,
                artifactRef,
                changeSetIds,
                baseWorkspaceDigest,
                resultWorkspaceDigest,
                fileSummaries,
                totalFileCount,
                summariesTruncated,
                counts,
                attributionStatus);
    }

    /** Compatibility overload for existing callers; new code should pass an explicit status. */
    public static CodingChangeReviewArtifact create(
            List<String> changeSetIds,
            String baseWorkspaceDigest,
            String resultWorkspaceDigest,
            List<FileSummary> fileSummaries,
            int totalFileCount,
            boolean summariesTruncated,
            Map<String, Integer> counts,
            boolean complete) {
        return create(
                changeSetIds,
                baseWorkspaceDigest,
                resultWorkspaceDigest,
                fileSummaries,
                totalFileCount,
                summariesTruncated,
                counts,
                complete ? AttributionStatus.COMPLETE : AttributionStatus.ATTRIBUTION_PARTIAL);
    }

    public boolean complete() {
        return attributionStatus == AttributionStatus.COMPLETE;
    }

    public Map<String, Object> toStructuredData() {
        List<Map<String, Object>> summaries =
                fileSummaries.stream().map(FileSummary::toStructuredData).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemaVersion", schemaVersion);
        data.put("artifactRef", artifactRef);
        data.put("changeSetIds", changeSetIds);
        data.put("baseWorkspaceDigest", baseWorkspaceDigest);
        data.put("resultWorkspaceDigest", resultWorkspaceDigest);
        data.put("fileSummaries", summaries);
        data.put("totalFileCount", totalFileCount);
        data.put("summariesTruncated", summariesTruncated);
        data.put("counts", counts);
        if (SCHEMA_VERSION.equals(schemaVersion)) {
            data.put("attributionStatus", attributionStatus.name());
        }
        data.put("complete", complete());
        return Map.copyOf(data);
    }

    public static Optional<CodingChangeReviewArtifact> fromStructuredData(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Optional.empty();
        try {
            List<String> changeSetIds = stringList(map.get("changeSetIds"));
            List<FileSummary> summaries = new ArrayList<>();
            Object rawSummaries = map.get("fileSummaries");
            if (!(rawSummaries instanceof List<?> values)) return Optional.empty();
            for (Object summary : values) {
                Optional<FileSummary> parsed = FileSummary.fromStructuredData(summary);
                if (parsed.isEmpty()) return Optional.empty();
                summaries.add(parsed.orElseThrow());
            }
            Map<String, Integer> counts = integerMap(map.get("counts"));
            int total = optionalInteger(map.get("totalFileCount"), summaries.size());
            boolean truncated = optionalBoolean(map.get("summariesTruncated"), total > summaries.size());
            String schemaVersion = text(map, "schemaVersion");
            AttributionStatus attributionStatus = LEGACY_SCHEMA_VERSION.equals(schemaVersion)
                    ? (bool(map, "complete") ? AttributionStatus.COMPLETE : AttributionStatus.ATTRIBUTION_PARTIAL)
                    : AttributionStatus.valueOf(text(map, "attributionStatus"));
            return Optional.of(new CodingChangeReviewArtifact(
                    schemaVersion,
                    text(map, "artifactRef"),
                    changeSetIds,
                    text(map, "baseWorkspaceDigest"),
                    text(map, "resultWorkspaceDigest"),
                    summaries,
                    total,
                    truncated,
                    counts,
                    attributionStatus));
        } catch (IllegalArgumentException | ClassCastException ignored) {
            return Optional.empty();
        }
    }

    public record FileSummary(
            FileChangeType changeType,
            String path,
            String destination,
            String beforeDigest,
            String afterDigest,
            long beforeSize,
            long afterSize,
            CodingChangeContentKind contentKind) {
        public FileSummary {
            changeType = Objects.requireNonNull(changeType, "changeType must not be null");
            path = bounded(path, "path", 512, false);
            destination = bounded(destination, "destination", 512, true);
            beforeDigest = optionalDigest(beforeDigest, "beforeDigest");
            afterDigest = optionalDigest(afterDigest, "afterDigest");
            if (beforeSize < -1 || afterSize < -1) {
                throw new IllegalArgumentException("file summary sizes must be -1 or non-negative");
            }
            contentKind = Objects.requireNonNull(contentKind, "contentKind must not be null");
        }

        Map<String, Object> toStructuredData() {
            return Map.ofEntries(
                    Map.entry("changeType", changeType.name()),
                    Map.entry("path", path),
                    Map.entry("destination", destination),
                    Map.entry("beforeDigest", beforeDigest),
                    Map.entry("afterDigest", afterDigest),
                    Map.entry("beforeSize", beforeSize),
                    Map.entry("afterSize", afterSize),
                    Map.entry("contentKind", contentKind.name()));
        }

        static Optional<FileSummary> fromStructuredData(Object value) {
            if (!(value instanceof Map<?, ?> map)) return Optional.empty();
            try {
                return Optional.of(new FileSummary(
                        FileChangeType.valueOf(text(map, "changeType")),
                        text(map, "path"),
                        optionalText(map, "destination"),
                        optionalText(map, "beforeDigest"),
                        optionalText(map, "afterDigest"),
                        number(map, "beforeSize"),
                        number(map, "afterSize"),
                        CodingChangeContentKind.valueOf(text(map, "contentKind"))));
            } catch (IllegalArgumentException | ClassCastException ignored) {
                return Optional.empty();
            }
        }
    }

    private static String digest(String value, String field) {
        String normalized = bounded(value, field, 71, false);
        if (!normalized.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a SHA-256 digest");
        }
        return normalized;
    }

    private static String optionalDigest(String value, String field) {
        String normalized = bounded(value, field, 71, true);
        return normalized.isEmpty() ? "" : digest(normalized, field);
    }

    private static String bounded(String value, String field, int maximum, boolean optional) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if ((!optional && normalized.isEmpty()) || normalized.length() > maximum) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static List<String> strings(List<String> values, String field, int maximumCount, int maximumLength) {
        List<String> copy = List.copyOf(Objects.requireNonNull(values, field + " must not be null"));
        if (copy.size() > maximumCount) throw new IllegalArgumentException(field + " exceeds its bound");
        return copy.stream()
                .map(value -> bounded(value, field, maximumLength, false))
                .toList();
    }

    private static String text(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException(key + " is invalid");
        return text;
    }

    private static String optionalText(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof String text ? text : "";
    }

    private static long number(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number number)) throw new IllegalArgumentException(key + " is invalid");
        return number.longValue();
    }

    private static boolean bool(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Boolean bool)) throw new IllegalArgumentException(key + " is invalid");
        return bool;
    }

    private static boolean optionalBoolean(Object value, boolean fallback) {
        return value instanceof Boolean bool ? bool : fallback;
    }

    private static int optionalInteger(Object value, int fallback) {
        if (value == null) return fallback;
        if (!(value instanceof Number number)) throw new IllegalArgumentException("integer value is invalid");
        return number.intValue();
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) throw new IllegalArgumentException("string list is invalid");
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof String text)) throw new IllegalArgumentException("string list item is invalid");
            result.add(text);
        }
        return result;
    }

    private static Map<String, Integer> integerMap(Object value) {
        if (!(value instanceof Map<?, ?> values)) throw new IllegalArgumentException("integer map is invalid");
        Map<String, Integer> result = new LinkedHashMap<>();
        values.forEach((key, item) -> {
            if (!(key instanceof String text) || !(item instanceof Number number)) {
                throw new IllegalArgumentException("integer map entry is invalid");
            }
            result.put(text, number.intValue());
        });
        return result;
    }

    private static String contentAddress(
            String schemaVersion,
            List<String> changeSetIds,
            String baseWorkspaceDigest,
            String resultWorkspaceDigest,
            List<FileSummary> fileSummaries,
            int totalFileCount,
            boolean summariesTruncated,
            Map<String, Integer> counts,
            AttributionStatus attributionStatus) {
        StringBuilder value = new StringBuilder(schemaVersion);
        append(value, String.join("|", changeSetIds));
        append(value, baseWorkspaceDigest);
        append(value, resultWorkspaceDigest);
        for (FileSummary summary : fileSummaries) {
            append(value, summary.changeType().name());
            append(value, summary.path());
            append(value, summary.destination());
            append(value, summary.beforeDigest());
            append(value, summary.afterDigest());
            append(value, Long.toString(summary.beforeSize()));
            append(value, Long.toString(summary.afterSize()));
            append(value, summary.contentKind().name());
        }
        append(value, Integer.toString(totalFileCount));
        append(value, Boolean.toString(summariesTruncated));
        COUNT_KEYS.forEach(key -> append(value, key + "=" + counts.get(key)));
        append(
                value,
                LEGACY_SCHEMA_VERSION.equals(schemaVersion)
                        ? Boolean.toString(attributionStatus == AttributionStatus.COMPLETE)
                        : attributionStatus.name());
        try {
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static void append(StringBuilder value, String field) {
        String normalized = Objects.requireNonNull(field, "content-address field must not be null");
        value.append('|').append(normalized.length()).append(':').append(normalized);
    }
}

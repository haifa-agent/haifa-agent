package io.haifa.agent.application.project.product.coding.delivery;

import io.haifa.agent.project.changeset.FileChange;
import io.haifa.agent.project.changeset.FileChangeSet;
import io.haifa.agent.project.changeset.FileChangeSetId;
import io.haifa.agent.project.changeset.FileChangeSetStatus;
import io.haifa.agent.project.changeset.FileChangeSetStore;
import io.haifa.agent.project.changeset.FileChangeType;
import io.haifa.agent.project.changeset.FileVersion;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Generates a bounded review document without requiring Git or a model-issued diff command. */
public final class CodingChangeReviewArtifactFactory {
    public static final long DEFAULT_OVERSIZE_THRESHOLD_BYTES = 512 * 1024;
    private final FileChangeSetStore changeSets;
    private final CodingChangeContentClassifier contentClassifier;
    private final long oversizeThresholdBytes;

    public CodingChangeReviewArtifactFactory(FileChangeSetStore changeSets) {
        this(changeSets, CodingChangeContentClassifier.opaque(), DEFAULT_OVERSIZE_THRESHOLD_BYTES);
    }

    public CodingChangeReviewArtifactFactory(
            FileChangeSetStore changeSets,
            CodingChangeContentClassifier contentClassifier,
            long oversizeThresholdBytes) {
        this.changeSets = Objects.requireNonNull(changeSets, "changeSets must not be null");
        this.contentClassifier = Objects.requireNonNull(contentClassifier, "contentClassifier must not be null");
        if (oversizeThresholdBytes < 1) {
            throw new IllegalArgumentException("oversizeThresholdBytes must be positive");
        }
        this.oversizeThresholdBytes = oversizeThresholdBytes;
    }

    public Optional<CodingChangeReviewArtifact> create(String runRef, List<String> changeSetIds) {
        String expectedRunRef = required(runRef, "runRef");
        List<String> ids = List.copyOf(Objects.requireNonNull(changeSetIds, "changeSetIds must not be null"));
        if (ids.isEmpty()
                || ids.size() > CodingChangeReviewArtifact.MAXIMUM_CHANGE_SET_IDS
                || ids.stream().anyMatch(id -> id == null || id.isBlank())) {
            return Optional.empty();
        }
        List<FileChangeSet> resolved = new ArrayList<>();
        for (String id : ids) {
            FileChangeSet changeSet = changeSets.find(new FileChangeSetId(id)).orElse(null);
            if (changeSet == null
                    || !expectedRunRef.equals(changeSet.runRef())
                    || (changeSet.status() != FileChangeSetStatus.APPLIED
                            && changeSet.status() != FileChangeSetStatus.RECONCILED)) {
                return Optional.empty();
            }
            resolved.add(changeSet);
        }
        resolved.sort(Comparator.comparing(FileChangeSet::createdAt)
                .thenComparing(changeSet -> changeSet.id().value()));
        if (resolved.stream().anyMatch(changeSet -> changeSet.changes().isEmpty())) return Optional.empty();

        Map<String, Integer> counts = emptyCounts();
        List<CodingChangeReviewArtifact.FileSummary> summaries = new ArrayList<>();
        int totalFiles = 0;
        for (FileChangeSet changeSet : resolved) {
            for (FileChange change : changeSet.changes()) {
                totalFiles++;
                CodingChangeContentKind contentKind = classify(changeSet, change);
                increment(counts, change.type());
                if (contentKind == CodingChangeContentKind.BINARY) counts.compute("binary", (key, value) -> value + 1);
                if (contentKind == CodingChangeContentKind.OVERSIZE) {
                    counts.compute("oversize", (key, value) -> value + 1);
                }
                if (contentKind == CodingChangeContentKind.OPAQUE) counts.compute("opaque", (key, value) -> value + 1);
                if (summaries.size() < CodingChangeReviewArtifact.MAXIMUM_FILE_SUMMARIES) {
                    summaries.add(summary(change, contentKind));
                }
            }
        }
        String base = normalizeDigest(resolved.getFirst().baseRevision().digest());
        String result = resolved.getLast()
                .optionalResultRevision()
                .map(revision -> normalizeDigest(revision.digest()))
                .orElseThrow();
        List<String> orderedIds =
                resolved.stream().map(value -> value.id().value()).toList();
        return Optional.of(CodingChangeReviewArtifact.create(
                orderedIds, base, result, summaries, totalFiles, totalFiles > summaries.size(), counts, true));
    }

    private CodingChangeContentKind classify(FileChangeSet changeSet, FileChange change) {
        long maximum = Math.max(size(change.before()), size(change.after()));
        if (maximum > oversizeThresholdBytes) return CodingChangeContentKind.OVERSIZE;
        CodingChangeContentKind classified = contentClassifier.classify(changeSet, change);
        return classified == null ? CodingChangeContentKind.OPAQUE : classified;
    }

    private static CodingChangeReviewArtifact.FileSummary summary(
            FileChange change, CodingChangeContentKind contentKind) {
        return new CodingChangeReviewArtifact.FileSummary(
                change.type(),
                reviewPath(change.path().value()),
                change.optionalDestination()
                        .map(value -> reviewPath(value.value()))
                        .orElse(""),
                digest(change.before()),
                digest(change.after()),
                size(change.before()),
                size(change.after()),
                contentKind);
    }

    private static String digest(FileVersion version) {
        return version == null ? "" : normalizeDigest(version.contentHash());
    }

    private static long size(FileVersion version) {
        return version == null ? -1 : version.size();
    }

    private static String normalizeDigest(String value) {
        String normalized = required(value, "digest");
        if (normalized.matches("sha256:[0-9a-f]{64}")) return normalized;
        return "sha256:" + digest(normalized);
    }

    private static void increment(Map<String, Integer> counts, FileChangeType type) {
        String key =
                switch (type) {
                    case CREATE -> "created";
                    case REPLACE -> "replaced";
                    case DELETE -> "deleted";
                    case MOVE -> "moved";
                };
        counts.compute(key, (ignored, value) -> value + 1);
    }

    private static Map<String, Integer> emptyCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String key : List.of("created", "replaced", "deleted", "moved", "binary", "oversize", "opaque")) {
            counts.put(key, 0);
        }
        return counts;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static String reviewPath(String value) {
        String normalized = required(value, "path");
        return normalized.length() <= 512 ? normalized : "path-sha256:" + digest(normalized);
    }

    private static String required(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}

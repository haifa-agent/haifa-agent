package io.haifa.agent.application.project.product.coding.delivery;

import io.haifa.agent.project.changeset.FileChangeType;
import io.haifa.agent.project.ledger.SessionChangeLedger;
import io.haifa.agent.project.ledger.SessionFileChangeRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Generates deterministic review documents on demand from Git and SessionChangeLedger. */
public final class OnDemandChangeReviewService {

    public static final long DEFAULT_OVERSIZE_THRESHOLD_BYTES = 512 * 1024;
    private static final List<String> COUNT_KEYS =
            List.of("created", "replaced", "deleted", "moved", "binary", "oversize", "opaque");

    private final SessionChangeLedger ledger;
    private final long oversizeThresholdBytes;

    public OnDemandChangeReviewService(SessionChangeLedger ledger) {
        this(ledger, DEFAULT_OVERSIZE_THRESHOLD_BYTES);
    }

    public OnDemandChangeReviewService(SessionChangeLedger ledger, long oversizeThresholdBytes) {
        this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
        if (oversizeThresholdBytes < 1) {
            throw new IllegalArgumentException("oversizeThresholdBytes must be positive");
        }
        this.oversizeThresholdBytes = oversizeThresholdBytes;
    }

    public Optional<CodingChangeReviewArtifact> generateReview(
            String runRef, String baseWorkspaceDigest, String resultWorkspaceDigest) {
        Objects.requireNonNull(runRef, "runRef must not be null");
        String base = normalizeDigest(
                baseWorkspaceDigest != null
                        ? baseWorkspaceDigest
                        : "sha256:0000000000000000000000000000000000000000000000000000000000000000");
        String result = normalizeDigest(
                resultWorkspaceDigest != null
                        ? resultWorkspaceDigest
                        : "sha256:1111111111111111111111111111111111111111111111111111111111111111");

        Map<String, Integer> counts = emptyCounts();
        List<CodingChangeReviewArtifact.FileSummary> summaries = new ArrayList<>();
        int totalFiles = 0;

        for (List<SessionFileChangeRecord> changes :
                ledger.allCompactedChanges().values()) {
            for (SessionFileChangeRecord change : changes) {
                totalFiles++;
                String pathStr = change.path().toString();
                String destStr =
                        change.sourcePath() != null ? change.sourcePath().toString() : "";

                increment(counts, change.type());
                counts.compute("opaque", (key, value) -> value + 1);

                if (summaries.size() < CodingChangeReviewArtifact.MAXIMUM_FILE_SUMMARIES) {
                    summaries.add(new CodingChangeReviewArtifact.FileSummary(
                            change.type(),
                            pathStr,
                            destStr,
                            optionalDigest(change.beforeHash()),
                            optionalDigest(change.afterHash()),
                            change.beforeSize() < 0 ? -1L : change.beforeSize(),
                            change.afterSize() < 0 ? 0L : change.afterSize(),
                            CodingChangeContentKind.OPAQUE));
                }
            }
        }

        List<String> changeSetIds = List.of("session-" + runRef);
        return Optional.of(CodingChangeReviewArtifact.create(
                changeSetIds, base, result, summaries, totalFiles, totalFiles > summaries.size(), counts, true));
    }

    private static String optionalDigest(String hash) {
        if (hash == null || hash.isBlank()) return "";
        if (hash.startsWith("sha256:") && hash.length() == 71) return hash;
        try {
            return "sha256:"
                    + java.util.HexFormat.of()
                            .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(hash.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Integer> emptyCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String key : COUNT_KEYS) counts.put(key, 0);
        return counts;
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

    private static String normalizeDigest(String digest) {
        if (digest.startsWith("sha256:")) return digest;
        return "sha256:" + digest;
    }
}

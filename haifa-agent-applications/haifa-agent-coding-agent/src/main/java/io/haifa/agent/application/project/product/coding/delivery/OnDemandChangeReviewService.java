package io.haifa.agent.application.project.product.coding.delivery;

import io.haifa.agent.project.changeset.FileChangeType;
import io.haifa.agent.project.ledger.SessionChangeLedger;
import io.haifa.agent.project.ledger.SessionFileChangeRecord;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.path.WorkspacePath;
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
    private final RunRepositoryBaselineRegistry repositories;
    private final RepositoryReviewCapture gitReviews;

    public OnDemandChangeReviewService(SessionChangeLedger ledger) {
        this(ledger, DEFAULT_OVERSIZE_THRESHOLD_BYTES);
    }

    public OnDemandChangeReviewService(SessionChangeLedger ledger, long oversizeThresholdBytes) {
        this(ledger, oversizeThresholdBytes, null, null);
    }

    public OnDemandChangeReviewService(
            SessionChangeLedger ledger,
            RunRepositoryBaselineRegistry repositories,
            RepositoryReviewCapture gitReviews) {
        this(ledger, DEFAULT_OVERSIZE_THRESHOLD_BYTES, repositories, gitReviews);
    }

    private OnDemandChangeReviewService(
            SessionChangeLedger ledger,
            long oversizeThresholdBytes,
            RunRepositoryBaselineRegistry repositories,
            RepositoryReviewCapture gitReviews) {
        this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
        if (oversizeThresholdBytes < 1) {
            throw new IllegalArgumentException("oversizeThresholdBytes must be positive");
        }
        this.oversizeThresholdBytes = oversizeThresholdBytes;
        this.repositories = repositories;
        this.gitReviews = gitReviews;
        if ((repositories == null) != (gitReviews == null)) {
            throw new IllegalArgumentException("repositories and gitReviews must be configured together");
        }
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
        boolean partial =
                repositories != null && repositories.attributionStatus(runRef) == AttributionStatus.ATTRIBUTION_PARTIAL;
        Map<WorkspacePath, ReviewTarget> assignments =
                repositories == null ? Map.of() : repositories.targetAssignments(runRef);

        for (List<SessionFileChangeRecord> changes :
                ledger.allCompactedChanges().values()) {
            for (SessionFileChangeRecord change : changes) {
                ReviewTarget target = assignments.get(change.path());
                if (target instanceof GitReviewTarget) continue;
                if (repositories != null && target == null) partial = true;
                totalFiles++;
                String pathStr = change.sourcePath() != null
                        ? change.sourcePath().toString()
                        : change.path().toString();
                String destStr = change.sourcePath() != null ? change.path().toString() : "";

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

        List<String> changeSetIds = new ArrayList<>();
        changeSetIds.add("session-" + runRef);
        if (repositories != null) {
            List<RepositoryBaseline> baselines = repositories.baselines(runRef).stream()
                    .sorted(java.util.Comparator.comparing(
                            baseline -> baseline.repository().root().toString()))
                    .toList();
            for (RepositoryBaseline baseline : baselines) {
                var evidence = gitReviews.capture(runRef, baseline);
                changeSetIds.add("git:" + evidence.evidenceDigest());
                if (!evidence.complete()) partial = true;
                for (var change : evidence.changes()) {
                    totalFiles++;
                    increment(counts, change.type());
                    counts.compute("opaque", (key, value) -> value + 1);
                    if (change.binary()) counts.compute("binary", (key, value) -> value + 1);
                    if (summaries.size() < CodingChangeReviewArtifact.MAXIMUM_FILE_SUMMARIES) {
                        WorkspacePath path = repositoryPath(baseline, change.path());
                        WorkspacePath destination =
                                change.destination() == null ? null : repositoryPath(baseline, change.destination());
                        summaries.add(new CodingChangeReviewArtifact.FileSummary(
                                change.type(),
                                path.toString(),
                                destination == null ? "" : destination.toString(),
                                "",
                                "",
                                -1,
                                -1,
                                change.binary() ? CodingChangeContentKind.BINARY : CodingChangeContentKind.OPAQUE));
                    }
                }
            }
        }
        return Optional.of(CodingChangeReviewArtifact.create(
                changeSetIds,
                base,
                result,
                summaries,
                totalFiles,
                totalFiles > summaries.size(),
                counts,
                partial ? AttributionStatus.ATTRIBUTION_PARTIAL : AttributionStatus.COMPLETE));
    }

    private static WorkspacePath repositoryPath(RepositoryBaseline baseline, ProjectPath relative) {
        ProjectPath root = baseline.repository().root().projectPath();
        ProjectPath combined = root.isRoot()
                ? relative
                : ProjectPath.of(root.value() + (relative.isRoot() ? "" : "/" + relative.value()));
        return new WorkspacePath(baseline.repository().root().workspaceId(), combined);
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

package io.haifa.agent.git;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.execution.api.ExecutionBroker;
import io.haifa.agent.execution.api.ExecutionStatus;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.project.changeset.FileChangeType;
import io.haifa.agent.project.path.ProjectPath;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Captures HEAD and a bounded porcelain-status digest without storing diff bodies. */
public final class ExecutionBrokerGitReviewProbe implements GitReviewProbe {
    private static final String EMPTY_STATUS_DIGEST =
            "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final int MAXIMUM_CHANGES = 2_000;
    private final ExecutionBrokerGitReadClient git;

    public ExecutionBrokerGitReviewProbe(
            ExecutionBroker broker, IdentifierGenerator identifiers, SandboxProfileRef profile, String gitExecutable) {
        this.git = new ExecutionBrokerGitReadClient(broker, identifiers, profile, gitExecutable);
    }

    @Override
    public GitReviewSnapshot captureBaseline(GitCommandContext context, GitRepositoryRef repository) {
        var head = git.run(context, repository.root(), List.of("rev-parse", "--verify", "HEAD"), 4096);
        String revision = head.status() == ExecutionStatus.SUCCEEDED
                ? head.stdout().summary().trim()
                : "";
        var status = git.run(
                context,
                repository.root(),
                List.of("status", "--porcelain=v1", "--untracked-files=normal"),
                256 * 1024);
        boolean complete =
                status.status() == ExecutionStatus.SUCCEEDED && !status.stdout().truncated();
        return new GitReviewSnapshot(revision, outputDigest(status.stdout().sha256()), complete);
    }

    @Override
    public GitReviewResult review(
            GitCommandContext context, GitRepositoryRef repository, String baselineDirtySnapshotDigest) {
        var status = git.run(
                context,
                repository.root(),
                List.of("status", "--porcelain=v1", "--untracked-files=normal"),
                256 * 1024);
        var diff = git.run(context, repository.root(), List.of("diff", "--numstat", "HEAD", "--"), 256 * 1024);
        String evidenceDigest =
                digest(status.stdout().sha256() + "\n" + diff.stdout().sha256());
        boolean commandsComplete = status.status() == ExecutionStatus.SUCCEEDED
                && diff.status() == ExecutionStatus.SUCCEEDED
                && !status.stdout().truncated()
                && !diff.stdout().truncated();
        List<GitReviewChange> changes;
        boolean parsed = true;
        try {
            changes = parseStatus(status.stdout().summary());
        } catch (IllegalArgumentException exception) {
            changes = List.of();
            parsed = false;
        }
        boolean truncated =
                status.stdout().truncated() || diff.stdout().truncated() || changes.size() >= MAXIMUM_CHANGES;
        boolean cleanBaseline = EMPTY_STATUS_DIGEST.equals(baselineDirtySnapshotDigest);
        return new GitReviewResult(
                evidenceDigest, changes, truncated, commandsComplete && parsed && cleanBaseline && !truncated);
    }

    private static List<GitReviewChange> parseStatus(String porcelain) {
        if (porcelain.isEmpty()) return List.of();
        String[] records = porcelain.split("\\R", -1);
        List<GitReviewChange> changes = new ArrayList<>();
        for (String record : records) {
            if (changes.size() >= MAXIMUM_CHANGES) break;
            if (record.isEmpty()) continue;
            if (record.length() < 4 || record.charAt(2) != ' ') {
                throw new IllegalArgumentException("invalid porcelain status record");
            }
            String state = record.substring(0, 2);
            String pathText = record.substring(3);
            if (pathText.startsWith("\"") || pathText.endsWith("\"")) {
                throw new IllegalArgumentException("quoted porcelain path is not safely attributable");
            }
            if (state.indexOf('R') >= 0 || state.indexOf('C') >= 0) {
                int separator = pathText.indexOf(" -> ");
                if (separator < 1 || separator + 4 >= pathText.length()) {
                    throw new IllegalArgumentException("rename path is invalid");
                }
                changes.add(new GitReviewChange(
                        FileChangeType.MOVE,
                        ProjectPath.of(pathText.substring(0, separator)),
                        ProjectPath.of(pathText.substring(separator + 4)),
                        false));
            } else {
                changes.add(new GitReviewChange(changeType(state), ProjectPath.of(pathText), null, false));
            }
        }
        return List.copyOf(changes);
    }

    private static FileChangeType changeType(String state) {
        if (state.equals("??") || state.indexOf('A') >= 0) return FileChangeType.CREATE;
        if (state.indexOf('D') >= 0) return FileChangeType.DELETE;
        return FileChangeType.REPLACE;
    }

    private static String digest(String value) {
        try {
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String outputDigest(String value) {
        return value.startsWith("sha256:") ? value : "sha256:" + value;
    }
}

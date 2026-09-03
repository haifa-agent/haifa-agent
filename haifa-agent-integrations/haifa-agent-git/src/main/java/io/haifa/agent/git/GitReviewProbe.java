package io.haifa.agent.git;

/** Internal bounded Git status/diff evidence path used by Coding review. */
public interface GitReviewProbe {
    GitReviewSnapshot captureBaseline(GitCommandContext context, GitRepositoryRef repository);

    GitReviewResult review(GitCommandContext context, GitRepositoryRef repository, String baselineDirtySnapshotDigest);
}

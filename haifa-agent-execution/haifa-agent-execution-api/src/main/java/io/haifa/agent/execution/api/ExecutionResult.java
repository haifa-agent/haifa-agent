package io.haifa.agent.execution.api;

import io.haifa.agent.project.changeset.FileChangeSetId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record ExecutionResult(
        ExecutionId id,
        ExecutionStatus status,
        Integer exitCode,
        Instant startedAt,
        Instant endedAt,
        ExecutionOutput stdout,
        ExecutionOutput stderr,
        FileChangeSetId fileChangeSetId,
        String sandboxSessionRef,
        ResourceUsageSummary resourceUsage,
        ExecutionFailure failure,
        boolean replayed) {
    public ExecutionResult {
        id = Objects.requireNonNull(id, "id must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
        endedAt = Objects.requireNonNull(endedAt, "endedAt must not be null");
        if (endedAt.isBefore(startedAt)) throw new IllegalArgumentException("endedAt precedes startedAt");
        stdout = Objects.requireNonNull(stdout, "stdout must not be null");
        stderr = Objects.requireNonNull(stderr, "stderr must not be null");
        sandboxSessionRef = Objects.requireNonNull(sandboxSessionRef, "sandboxSessionRef must not be null");
        resourceUsage = Objects.requireNonNull(resourceUsage, "resourceUsage must not be null");
    }

    public Optional<Integer> optionalExitCode() {
        return Optional.ofNullable(exitCode);
    }

    public Optional<FileChangeSetId> optionalFileChangeSetId() {
        return Optional.ofNullable(fileChangeSetId);
    }

    public Optional<ExecutionFailure> optionalFailure() {
        return Optional.ofNullable(failure);
    }

    public ExecutionResult asReplay() {
        return new ExecutionResult(
                id,
                status,
                exitCode,
                startedAt,
                endedAt,
                stdout,
                stderr,
                fileChangeSetId,
                sandboxSessionRef,
                resourceUsage,
                failure,
                true);
    }
}

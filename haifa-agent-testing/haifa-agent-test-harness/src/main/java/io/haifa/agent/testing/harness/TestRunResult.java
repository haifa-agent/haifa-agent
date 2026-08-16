package io.haifa.agent.testing.harness;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Authoritative shared result stored once per run or repeat. */
public record TestRunResult(
        int schemaVersion,
        Status status,
        String nativeStatus,
        Instant startedAt,
        Instant finishedAt,
        Failure failureClassification,
        Map<String, Object> usage,
        List<Map<String, Object>> cases,
        List<EvidenceAttachment> evidenceReferences,
        Map<String, Object> extensions) {
    public TestRunResult {
        if (schemaVersion != 1) throw new IllegalArgumentException("run result schemaVersion must be 1");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(nativeStatus, "nativeStatus must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(finishedAt, "finishedAt must not be null");
        if (finishedAt.isBefore(startedAt)) throw new IllegalArgumentException("finishedAt must not precede startedAt");
        Objects.requireNonNull(failureClassification, "failureClassification must not be null");
        usage = Map.copyOf(Objects.requireNonNullElse(usage, Map.of()));
        cases = List.copyOf(Objects.requireNonNullElse(cases, List.of()));
        evidenceReferences = List.copyOf(Objects.requireNonNullElse(evidenceReferences, List.of()));
        extensions = Map.copyOf(Objects.requireNonNullElse(extensions, Map.of()));
    }

    public enum Status {
        PASSED,
        FAILED,
        ERROR,
        TIMEOUT,
        SKIPPED,
        NOT_RUN
    }

    public enum Failure {
        NONE,
        CONFIGURATION_REJECTED,
        AUTHORIZATION_REJECTED,
        PROVISION_FAILED,
        NOT_STARTED,
        EXECUTION_FAILED,
        ACCEPTANCE_FAILED,
        EVIDENCE_FAILED,
        CLEANUP_FAILED,
        TIMEOUT
    }
}

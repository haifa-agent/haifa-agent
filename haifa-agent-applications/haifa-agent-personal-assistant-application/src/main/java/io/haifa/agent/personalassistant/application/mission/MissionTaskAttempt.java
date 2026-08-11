package io.haifa.agent.personalassistant.application.mission;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** One durable Task dispatch/settlement attempt. */
public record MissionTaskAttempt(
        String missionId,
        String taskId,
        int attemptNo,
        String attemptKind,
        String dispatchKey,
        String dispatchPayloadDigest,
        MissionTaskAttemptState state,
        Optional<String> sessionId,
        Optional<String> runId,
        Optional<String> resultDigest,
        Optional<String> failureCode,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Optional<Instant> startedAt,
        Optional<Instant> settledAt) {
    public MissionTaskAttempt {
        missionId = MissionValues.text(missionId, "missionId", 256);
        taskId = MissionValues.text(taskId, "taskId", 64);
        if (attemptNo < 1) throw new MissionException("MISSION_ATTEMPT_INVALID", "attemptNo must be positive");
        attemptKind = MissionValues.text(attemptKind, "attemptKind", 32);
        dispatchKey = MissionValues.text(dispatchKey, "dispatchKey", 512);
        dispatchPayloadDigest = MissionValues.text(dispatchPayloadDigest, "dispatchPayloadDigest", 128);
        state = Objects.requireNonNull(state);
        sessionId = Objects.requireNonNull(sessionId);
        runId = Objects.requireNonNull(runId);
        resultDigest = Objects.requireNonNull(resultDigest);
        failureCode = Objects.requireNonNull(failureCode);
        createdAt = MissionValues.millisecond(createdAt, "createdAt");
        updatedAt = MissionValues.millisecond(updatedAt, "updatedAt");
        startedAt = Objects.requireNonNull(startedAt).map(value -> MissionValues.millisecond(value, "startedAt"));
        settledAt = Objects.requireNonNull(settledAt).map(value -> MissionValues.millisecond(value, "settledAt"));
    }
}

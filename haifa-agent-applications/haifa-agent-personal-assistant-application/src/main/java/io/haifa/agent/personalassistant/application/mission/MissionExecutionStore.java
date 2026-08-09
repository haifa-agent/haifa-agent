package io.haifa.agent.personalassistant.application.mission;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Product persistence port for the Mission Task dispatch/settlement Saga. */
public interface MissionExecutionStore {
    Optional<MissionDispatchIntent> prepareAndClaimNext(String dispatcherId, Instant now, Instant staleBefore);

    void bind(MissionDispatchIntent intent, String sessionId, String runId, Instant now);

    void failDispatch(MissionDispatchIntent intent, String failureCode, boolean retryable, Instant now);

    List<MissionTaskAttempt> activeAttempts();

    MissionState missionState(String missionId);

    default boolean deadlineExceeded(String missionId, Instant now) {
        return false;
    }

    default void expireForPartialSynthesis(String missionId, Instant now) {}

    void waitingForUser(MissionTaskAttempt attempt, Instant now);

    void settleCompleted(MissionTaskAttempt attempt, String resultDigest, String resultJson, Instant now);

    default void settleCompleted(
            MissionTaskAttempt attempt, String resultDigest, String resultJson, MissionUsage usage, Instant now) {
        settleCompleted(attempt, resultDigest, resultJson, now);
    }

    void settleFailed(MissionTaskAttempt attempt, String failureCode, boolean retryable, Instant now);

    default void settleFailed(
            MissionTaskAttempt attempt, String failureCode, boolean retryable, MissionUsage usage, Instant now) {
        settleFailed(attempt, failureCode, retryable, now);
    }

    void settleCancelled(MissionTaskAttempt attempt, Instant now);

    default void settleCancelled(MissionTaskAttempt attempt, MissionUsage usage, Instant now) {
        settleCancelled(attempt, now);
    }

    default void cancelMission(String missionId, Instant now) {}

    MissionExecutionSnapshot snapshot(String missionId);

    void retryBlocked(String missionId, String ownerScope, String taskId, Instant now);

    default Optional<MissionSynthesisIntent> claimSynthesis(Instant now) {
        return Optional.empty();
    }

    default void settleSynthesis(
            MissionSynthesisIntent intent,
            MissionRuntimeAccess.SynthesisRunResult synthesis,
            MissionPublishedResult published,
            Instant now) {}

    default void failSynthesis(MissionSynthesisIntent intent, String failureCode, Instant now) {}

    static MissionExecutionStore unavailable() {
        return new MissionExecutionStore() {
            @Override
            public Optional<MissionDispatchIntent> prepareAndClaimNext(String id, Instant now, Instant stale) {
                return Optional.empty();
            }

            @Override
            public void bind(MissionDispatchIntent intent, String sessionId, String runId, Instant now) {}

            @Override
            public void failDispatch(MissionDispatchIntent intent, String code, boolean retryable, Instant now) {}

            @Override
            public List<MissionTaskAttempt> activeAttempts() {
                return List.of();
            }

            @Override
            public MissionState missionState(String missionId) {
                return MissionState.FAILED;
            }

            @Override
            public void waitingForUser(MissionTaskAttempt attempt, Instant now) {}

            @Override
            public void settleCompleted(MissionTaskAttempt attempt, String digest, String json, Instant now) {}

            @Override
            public void settleFailed(MissionTaskAttempt attempt, String code, boolean retryable, Instant now) {}

            @Override
            public void settleCancelled(MissionTaskAttempt attempt, Instant now) {}

            @Override
            public MissionExecutionSnapshot snapshot(String missionId) {
                return MissionExecutionSnapshot.unavailable();
            }

            @Override
            public void retryBlocked(String missionId, String ownerScope, String taskId, Instant now) {
                throw new MissionException("MISSION_EXECUTION_UNAVAILABLE", "Mission execution is unavailable");
            }
        };
    }
}

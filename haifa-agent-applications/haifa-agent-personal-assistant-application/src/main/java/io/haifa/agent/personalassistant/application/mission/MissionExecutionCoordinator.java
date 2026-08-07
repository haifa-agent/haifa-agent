package io.haifa.agent.personalassistant.application.mission;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Single-dispatcher orchestration for the product UoW -> Runtime -> product UoW Saga. */
public final class MissionExecutionCoordinator {
    private final MissionExecutionStore store;
    private final MissionRuntimeAccess runtime;
    private final Clock clock;
    private final String dispatcherId;

    public MissionExecutionCoordinator(
            MissionExecutionStore store, MissionRuntimeAccess runtime, Clock clock, String dispatcherId) {
        this.store = Objects.requireNonNull(store);
        this.runtime = Objects.requireNonNull(runtime);
        this.clock = Objects.requireNonNull(clock);
        this.dispatcherId = MissionValues.text(dispatcherId, "dispatcherId", 256);
    }

    /** Performs one bounded reconciliation and dispatch cycle. Safe to invoke again after any process crash. */
    public void tick() {
        Instant now = now();
        for (MissionTaskAttempt attempt : store.activeAttempts()) {
            if (attempt.state() != MissionTaskAttemptState.BOUND
                    || attempt.runId().isEmpty()) continue;
            if (store.missionState(attempt.missionId()).terminal()) {
                runtime.cancelTask(attempt.runId().orElseThrow());
                store.settleCancelled(attempt, now);
                continue;
            }
            MissionRuntimeAccess.TaskRunObservation observation =
                    runtime.observeTask(attempt.runId().orElseThrow());
            switch (observation.state()) {
                case ACTIVE -> {}
                case WAITING_USER -> store.waitingForUser(attempt, now);
                case COMPLETED -> {
                    String result = observation.result().orElse("");
                    store.settleCompleted(attempt, MissionValues.digest(result), result, now);
                }
                case FAILED ->
                    store.settleFailed(attempt, observation.failureCode().orElse("TASK_RUN_FAILED"), true, now);
                case CANCELLED -> store.settleCancelled(attempt, now);
                case OUTCOME_UNKNOWN -> store.settleFailed(attempt, "TASK_RUN_OUTCOME_UNKNOWN", false, now);
            }
        }

        store.prepareAndClaimNext(dispatcherId, now, now.minus(30, ChronoUnit.SECONDS))
                .ifPresent(intent -> {
                    MissionRuntimeAccess.TaskRunBinding binding;
                    try {
                        binding = runtime.startTask(intent);
                    } catch (RuntimeException failure) {
                        store.failDispatch(intent, safeCode(failure), false, now());
                        return;
                    }
                    // Deliberately outside the start catch: a failed product bind leaves the claimed Outbox
                    // recoverable, and the stable Runtime idempotency key returns the same Run next cycle.
                    store.bind(intent, binding.sessionId(), binding.runId(), now());
                });
    }

    private Instant now() {
        return Instant.ofEpochMilli(clock.instant().toEpochMilli());
    }

    private static String safeCode(RuntimeException failure) {
        if (failure instanceof MissionException mission) return mission.code();
        if (failure instanceof io.haifa.agent.runtime.api.RuntimeContractException runtime) {
            return runtime.code().name();
        }
        return "MISSION_DISPATCH_FAILED";
    }
}

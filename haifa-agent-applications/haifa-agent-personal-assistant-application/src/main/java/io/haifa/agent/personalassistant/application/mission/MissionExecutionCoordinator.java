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
    private final MissionResultPublisher publisher;

    public MissionExecutionCoordinator(
            MissionExecutionStore store, MissionRuntimeAccess runtime, Clock clock, String dispatcherId) {
        this(store, runtime, clock, dispatcherId, MissionResultPublisher.unavailable());
    }

    public MissionExecutionCoordinator(
            MissionExecutionStore store,
            MissionRuntimeAccess runtime,
            Clock clock,
            String dispatcherId,
            MissionResultPublisher publisher) {
        this.store = Objects.requireNonNull(store);
        this.runtime = Objects.requireNonNull(runtime);
        this.clock = Objects.requireNonNull(clock);
        this.dispatcherId = MissionValues.text(dispatcherId, "dispatcherId", 256);
        this.publisher = Objects.requireNonNull(publisher);
    }

    /** Performs one bounded reconciliation and dispatch cycle. Safe to invoke again after any process crash. */
    public void tick() {
        tick(true);
    }

    /** Reconciliation always runs; capacity or maintenance gates may independently stop new dispatch claims. */
    public void tick(boolean allowNewDispatch) {
        Instant now = now();
        for (MissionTaskAttempt attempt : store.activeAttempts()) {
            if (attempt.state() != MissionTaskAttemptState.BOUND
                    || attempt.runId().isEmpty()) continue;
            if (store.missionState(attempt.missionId()).terminal()) {
                runtime.cancelTask(attempt.runId().orElseThrow());
                store.settleCancelled(attempt, MissionUsage.NONE, now);
                continue;
            }
            if (store.deadlineExceeded(attempt.missionId(), now)) {
                runtime.cancelTask(attempt.runId().orElseThrow());
                store.settleCancelled(attempt, MissionUsage.NONE, now);
                store.expireForPartialSynthesis(attempt.missionId(), now);
                continue;
            }
            MissionRuntimeAccess.TaskRunObservation observation =
                    runtime.observeTask(attempt.runId().orElseThrow());
            switch (observation.state()) {
                case ACTIVE -> {}
                case WAITING_USER -> store.waitingForUser(attempt, now);
                case COMPLETED -> {
                    String result = observation.result().orElse("");
                    store.settleCompleted(attempt, MissionValues.digest(result), result, observation.usage(), now);
                }
                case FAILED -> {
                    String failureCode = observation.failureCode().orElse("TASK_RUN_FAILED");
                    store.settleFailed(
                            attempt, failureCode, retryableTaskFailure(failureCode), observation.usage(), now);
                }
                case CANCELLED -> store.settleCancelled(attempt, observation.usage(), now);
                case OUTCOME_UNKNOWN ->
                    store.settleFailed(attempt, "TASK_RUN_OUTCOME_UNKNOWN", false, observation.usage(), now);
            }
        }

        if (allowNewDispatch) {
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

        store.claimSynthesis(now()).ifPresent(intent -> {
            MissionRuntimeAccess.SynthesisRunResult synthesis;
            MissionPublishedResult published;
            try {
                synthesis = runtime.runSynthesis(intent);
                PublishedSynthesis delivery =
                        publishWithBoundedSynthesisRecovery(publisher, runtime, intent, synthesis, this::now);
                synthesis = delivery.synthesis();
                published = delivery.published();
            } catch (MissionException failure) {
                if (recoverableSynthesisFailure(failure.code())) return;
                store.failSynthesis(intent, failure.code(), now());
                return;
            } catch (RuntimeException transientFailure) {
                return;
            }
            try {
                runtime.appendFinalMessage(
                        intent.conversationId(), intent.missionId(), synthesis.runId(), published.finalMessage());
            } catch (RuntimeException transientFailure) {
                return;
            }
            try {
                store.settleSynthesis(intent, synthesis, published, now());
            } catch (RuntimeException transientFailure) {
                // The transaction outcome may be unknown. Reclaiming SYNTHESIZING and replaying stable Runtime,
                // Artifact, and final-message keys is safe; a committed settlement is no longer claimable.
            }
        });
    }

    static PublishedSynthesis publishWithBoundedSynthesisRecovery(
            MissionResultPublisher publisher,
            MissionRuntimeAccess runtime,
            MissionSynthesisIntent intent,
            MissionRuntimeAccess.SynthesisRunResult synthesis,
            java.util.function.Supplier<Instant> now) {
        if (intent.mode() != MissionMode.DEEP_RESEARCH) {
            return publishWithSingleStandardRepair(publisher, runtime, intent, synthesis);
        }
        MissionUsage cumulativeUsage = synthesis.usage();
        ReportQualityGate.Result quality = publisher.evaluate(intent, synthesis);
        int revisionAttempt = 1;
        while (!quality.passed() && intent.revisionAllowed(revisionAttempt, cumulativeUsage, now.get())) {
            MissionRuntimeAccess.SynthesisRunResult revised =
                    runtime.reviseSynthesis(intent, synthesis, quality, revisionAttempt);
            cumulativeUsage = cumulativeUsage.plus(revised.usage());
            synthesis = new MissionRuntimeAccess.SynthesisRunResult(
                    revised.sessionId(),
                    revised.runId(),
                    revised.structuredOutput(),
                    cumulativeUsage,
                    revised.degradationReasons(),
                    revisionAttempt);
            quality = publisher.evaluate(intent, synthesis);
            revisionAttempt++;
        }
        MissionPublishedResult published = quality.passed()
                ? publisher.publish(intent, synthesis)
                : publisher.publishDegraded(intent, synthesis, quality);
        return new PublishedSynthesis(synthesis, published);
    }

    private static PublishedSynthesis publishWithSingleStandardRepair(
            MissionResultPublisher publisher,
            MissionRuntimeAccess runtime,
            MissionSynthesisIntent intent,
            MissionRuntimeAccess.SynthesisRunResult synthesis) {
        try {
            return new PublishedSynthesis(synthesis, publisher.publish(intent, synthesis));
        } catch (MissionException invalid) {
            if (!"MISSION_RESULT_SCHEMA_INVALID".equals(invalid.code())
                    && !"MISSION_REPORT_QUALITY_FAILED".equals(invalid.code())) throw invalid;
            MissionRuntimeAccess.SynthesisRunResult repaired =
                    runtime.repairSynthesis(intent, synthesis, invalid.code(), invalid.getMessage(), 1);
            repaired = new MissionRuntimeAccess.SynthesisRunResult(
                    repaired.sessionId(),
                    repaired.runId(),
                    repaired.structuredOutput(),
                    synthesis.usage().plus(repaired.usage()),
                    repaired.degradationReasons(),
                    repaired.qualityGateRevisionCount());
            return new PublishedSynthesis(repaired, publisher.publish(intent, repaired));
        }
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

    private static boolean recoverableSynthesisFailure(String failureCode) {
        return "MISSION_SYNTHESIS_TIMEOUT".equals(failureCode)
                || "MISSION_SYNTHESIS_INTERRUPTED".equals(failureCode)
                || "MISSION_SYNTHESIS_REPAIR_TIMEOUT".equals(failureCode)
                || "MISSION_SYNTHESIS_REPAIR_INTERRUPTED".equals(failureCode);
    }

    static boolean retryableTaskFailure(String failureCode) {
        return !failureCode.startsWith("MISSION_TASK_NORMALIZATION_");
    }

    record PublishedSynthesis(MissionRuntimeAccess.SynthesisRunResult synthesis, MissionPublishedResult published) {}
}

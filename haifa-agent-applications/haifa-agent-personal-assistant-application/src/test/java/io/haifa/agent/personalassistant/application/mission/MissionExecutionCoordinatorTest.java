package io.haifa.agent.personalassistant.application.mission;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MissionExecutionCoordinatorTest {
    @Test
    void doesNotRepeatCompletedResearchWhenOnlyNormalizationFails() {
        assertThat(MissionExecutionCoordinator.retryableTaskFailure("MISSION_TASK_NORMALIZATION_FAILED"))
                .isFalse();
        assertThat(MissionExecutionCoordinator.retryableTaskFailure("MODEL_RESPONSE_INVALID"))
                .isTrue();
    }

    @Test
    void stopsAfterTheFirstQualityApprovedCandidate() {
        var revisions = new AtomicInteger();
        var delivery = MissionExecutionCoordinator.publishWithBoundedResearchRevisions(
                publisher(0),
                runtime(revisions),
                intent(2, 1_000),
                synthesis("initial", 100),
                MissionExecutionCoordinatorTest::now);

        assertThat(revisions).hasValue(0);
        assertThat(delivery.synthesis().structuredOutput()).isEqualTo("initial");
        assertThat(delivery.published().completionKind()).isEqualTo("COMPLETE");
    }

    @Test
    void supportsOneAndTwoBoundedRevisionsWithCumulativeUsage() {
        for (int failuresBeforePass : List.of(1, 2)) {
            var revisions = new AtomicInteger();
            var delivery = MissionExecutionCoordinator.publishWithBoundedResearchRevisions(
                    publisher(failuresBeforePass),
                    runtime(revisions),
                    intent(2, 1_000),
                    synthesis("initial", 100),
                    MissionExecutionCoordinatorTest::now);

            assertThat(revisions).hasValue(failuresBeforePass);
            assertThat(delivery.synthesis().structuredOutput()).isEqualTo("revision-" + failuresBeforePass);
            assertThat(delivery.synthesis().usage().modelTokens()).isEqualTo(100L + 10L * failuresBeforePass);
            assertThat(delivery.published().completionKind()).isEqualTo("COMPLETE");
        }
    }

    @Test
    void revisionAndBudgetExhaustionPublishPartialDegradedResult() {
        for (MissionSynthesisIntent request : List.of(intent(2, 1_000), intent(2, 100))) {
            var revisions = new AtomicInteger();
            var delivery = MissionExecutionCoordinator.publishWithBoundedResearchRevisions(
                    publisher(3),
                    runtime(revisions),
                    request,
                    synthesis("initial", 100),
                    MissionExecutionCoordinatorTest::now);

            assertThat(delivery.published().completionKind()).isEqualTo("PARTIAL");
            assertThat(revisions.get()).isBetween(0, 2);
        }
    }

    @Test
    void reclaimsSynthesisAfterPublicationFinalMessageOrSettlementInterruption() {
        for (Interruption interruption : Interruption.values()) {
            var store = new RecoveryStore(intent(2, 1_000), interruption);
            var runtime = new RecoveryRuntime(interruption);
            var publisher = new RecoveryPublisher(interruption);
            var coordinator = new MissionExecutionCoordinator(
                    store, runtime, Clock.fixed(now(), ZoneOffset.UTC), "dispatcher-1", publisher);

            coordinator.tick(false);
            assertThat(store.failed)
                    .as("%s must remain recoverable", interruption)
                    .isZero();
            assertThat(store.settled).as("%s first attempt", interruption).isFalse();

            coordinator.tick(false);
            assertThat(store.failed).isZero();
            assertThat(store.settled).as("%s replay", interruption).isTrue();
            assertThat(runtime.uniqueFinalMessages).hasSize(1);
        }
    }

    private static Instant now() {
        return Instant.parse("2026-08-10T00:00:00Z");
    }

    private static MissionSynthesisIntent intent(int revisions, long remainingTokens) {
        return new MissionSynthesisIntent(
                "mission-1",
                "conversation-1",
                "owner-1",
                MissionMode.DEEP_RESEARCH,
                "Research objective",
                List.of("{}"),
                List.of(),
                List.of("evidence-task"),
                revisions,
                remainingTokens,
                Optional.of(Instant.parse("2026-08-10T01:00:00Z")));
    }

    private static MissionRuntimeAccess runtime(AtomicInteger revisions) {
        return new MissionRuntimeAccess() {
            @Override
            public PlannerRunResult runPlanner(MissionPlanner.PlanningRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public SynthesisRunResult reviseSynthesis(
                    MissionSynthesisIntent intent,
                    SynthesisRunResult previous,
                    ReportQualityGate.Result quality,
                    int revisionAttempt) {
                revisions.incrementAndGet();
                return synthesis("revision-" + revisionAttempt, 10);
            }
        };
    }

    private static MissionResultPublisher publisher(int failuresBeforePass) {
        return new MissionResultPublisher() {
            private int evaluations;

            @Override
            public ReportQualityGate.Result evaluate(
                    MissionSynthesisIntent intent, MissionRuntimeAccess.SynthesisRunResult synthesis) {
                return evaluations++ < failuresBeforePass
                        ? new ReportQualityGate.Result(
                                false,
                                List.of(new ReportQualityGate.Failure(
                                        "REPORT_REQUIRED_SECTION_MISSING", List.of("synthesis"))))
                        : ReportQualityGate.Result.passedResult();
            }

            @Override
            public MissionPublishedResult publish(
                    MissionSynthesisIntent intent, MissionRuntimeAccess.SynthesisRunResult synthesis) {
                return published("COMPLETE", synthesis.structuredOutput());
            }

            @Override
            public MissionPublishedResult publishDegraded(
                    MissionSynthesisIntent intent,
                    MissionRuntimeAccess.SynthesisRunResult synthesis,
                    ReportQualityGate.Result quality) {
                return published("PARTIAL", synthesis.structuredOutput());
            }
        };
    }

    private static MissionPublishedResult published(String completion, String report) {
        return new MissionPublishedResult(
                "artifact-report", List.of("artifact-report"), List.of(), "{}", report, completion);
    }

    private static MissionRuntimeAccess.SynthesisRunResult synthesis(String value, long tokens) {
        return new MissionRuntimeAccess.SynthesisRunResult(
                "session-1", "run-" + value, value, new MissionUsage(tokens, 1, 0));
    }

    private enum Interruption {
        PUBLICATION,
        FINAL_MESSAGE,
        SETTLEMENT
    }

    private static final class RecoveryRuntime implements MissionRuntimeAccess {
        private final Interruption interruption;
        private final AtomicInteger finalMessageAttempts = new AtomicInteger();
        private final Set<String> uniqueFinalMessages = new HashSet<>();

        private RecoveryRuntime(Interruption interruption) {
            this.interruption = interruption;
        }

        @Override
        public PlannerRunResult runPlanner(MissionPlanner.PlanningRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SynthesisRunResult runSynthesis(MissionSynthesisIntent intent) {
            return synthesis("stable-report", 10);
        }

        @Override
        public void appendFinalMessage(
                String conversationId, String missionId, String synthesisRunId, String finalMessage) {
            int attempt = finalMessageAttempts.incrementAndGet();
            if (interruption == Interruption.FINAL_MESSAGE && attempt == 1) {
                throw new IllegalStateException("injected final-message interruption");
            }
            uniqueFinalMessages.add("mission:" + missionId + ":final-message:v1");
        }
    }

    private static final class RecoveryPublisher implements MissionResultPublisher {
        private final Interruption interruption;
        private final AtomicInteger attempts = new AtomicInteger();

        private RecoveryPublisher(Interruption interruption) {
            this.interruption = interruption;
        }

        @Override
        public ReportQualityGate.Result evaluate(
                MissionSynthesisIntent intent, MissionRuntimeAccess.SynthesisRunResult synthesis) {
            return ReportQualityGate.Result.passedResult();
        }

        @Override
        public MissionPublishedResult publish(
                MissionSynthesisIntent intent, MissionRuntimeAccess.SynthesisRunResult synthesis) {
            if (interruption == Interruption.PUBLICATION && attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("injected publication interruption");
            }
            return published("COMPLETE", synthesis.structuredOutput());
        }
    }

    private static final class RecoveryStore implements MissionExecutionStore {
        private final MissionSynthesisIntent intent;
        private final Interruption interruption;
        private final AtomicInteger settlementAttempts = new AtomicInteger();
        private int failed;
        private boolean settled;

        private RecoveryStore(MissionSynthesisIntent intent, Interruption interruption) {
            this.intent = intent;
            this.interruption = interruption;
        }

        @Override
        public Optional<MissionDispatchIntent> prepareAndClaimNext(
                String dispatcherId, Instant now, Instant staleBefore) {
            return Optional.empty();
        }

        @Override
        public void bind(MissionDispatchIntent intent, String sessionId, String runId, Instant now) {}

        @Override
        public void failDispatch(MissionDispatchIntent intent, String failureCode, boolean retryable, Instant now) {}

        @Override
        public List<MissionTaskAttempt> activeAttempts() {
            return List.of();
        }

        @Override
        public MissionState missionState(String missionId) {
            return settled ? MissionState.COMPLETED : MissionState.SYNTHESIZING;
        }

        @Override
        public void waitingForUser(MissionTaskAttempt attempt, Instant now) {}

        @Override
        public void settleCompleted(MissionTaskAttempt attempt, String resultDigest, String resultJson, Instant now) {}

        @Override
        public void settleFailed(MissionTaskAttempt attempt, String failureCode, boolean retryable, Instant now) {}

        @Override
        public void settleCancelled(MissionTaskAttempt attempt, Instant now) {}

        @Override
        public MissionExecutionSnapshot snapshot(String missionId) {
            return MissionExecutionSnapshot.unavailable();
        }

        @Override
        public void retryBlocked(String missionId, String ownerScope, String taskId, Instant now) {}

        @Override
        public Optional<MissionSynthesisIntent> claimSynthesis(Instant now) {
            return settled ? Optional.empty() : Optional.of(intent);
        }

        @Override
        public void settleSynthesis(
                MissionSynthesisIntent intent,
                MissionRuntimeAccess.SynthesisRunResult synthesis,
                MissionPublishedResult published,
                Instant now) {
            if (interruption == Interruption.SETTLEMENT && settlementAttempts.incrementAndGet() == 1) {
                throw new IllegalStateException("injected settlement interruption");
            }
            settled = true;
        }

        @Override
        public void failSynthesis(MissionSynthesisIntent intent, String failureCode, Instant now) {
            failed++;
        }
    }
}

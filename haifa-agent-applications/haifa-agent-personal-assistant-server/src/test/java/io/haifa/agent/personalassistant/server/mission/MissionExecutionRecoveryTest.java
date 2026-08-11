package io.haifa.agent.personalassistant.server.mission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.personalassistant.application.mission.DeterministicMissionPlanner;
import io.haifa.agent.personalassistant.application.mission.MissionApplicationService;
import io.haifa.agent.personalassistant.application.mission.MissionConstraints;
import io.haifa.agent.personalassistant.application.mission.MissionDispatchIntent;
import io.haifa.agent.personalassistant.application.mission.MissionExecutionCoordinator;
import io.haifa.agent.personalassistant.application.mission.MissionExecutionSnapshot;
import io.haifa.agent.personalassistant.application.mission.MissionExecutionStore;
import io.haifa.agent.personalassistant.application.mission.MissionPlanValidator;
import io.haifa.agent.personalassistant.application.mission.MissionRuntimeAccess;
import io.haifa.agent.personalassistant.application.mission.MissionState;
import io.haifa.agent.personalassistant.application.mission.MissionTaskAttempt;
import io.haifa.agent.personalassistant.application.mission.MissionTaskAttemptState;
import io.haifa.agent.personalassistant.application.mission.MissionTaskState;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MissionExecutionRecoveryTest {
    private static final Instant START = Instant.parse("2026-08-08T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(START, ZoneOffset.UTC);
    private static final String OWNER = "local/public-user";

    @TempDir
    Path directory;

    @Test
    void restartsBetweenDependentTasksWithoutDuplicateRunsAndSettlesSerially() {
        Path database = directory.resolve("restart.sqlite");
        AtomicInteger ids = new AtomicInteger();
        FakeRuntime runtime = new FakeRuntime();
        SqliteMissionStore first = store(database);
        MissionApplicationService service = service(first, ids);
        var created = service.create(create(
                "create-three",
                "conversation-three",
                "Execute three dependent tasks",
                List.of("first", "second", "third")));
        var confirmed = service.confirm(change("confirm-three", created.missionId(), created.version()));

        coordinator(first, runtime, CLOCK).tick();
        assertSingleBoundAttempt(first, 1);
        runtime.complete(first.activeAttempts().getFirst(), "result-1");

        SqliteMissionStore second = store(database);
        coordinator(second, runtime, CLOCK).tick();
        assertSingleBoundAttempt(second, 2);
        runtime.complete(second.activeAttempts().getFirst(), "result-2");

        SqliteMissionStore third = store(database);
        coordinator(third, runtime, CLOCK).tick();
        assertSingleBoundAttempt(third, 3);
        runtime.complete(third.activeAttempts().getFirst(), "result-3");

        SqliteMissionStore fourth = store(database);
        coordinator(fourth, runtime, CLOCK).tick();
        MissionExecutionSnapshot execution = fourth.snapshot(confirmed.missionId());
        assertThat(execution.allTasksSettled()).isTrue();
        assertThat(execution.completedTasks()).isEqualTo(3);
        assertThat(execution.tasks())
                .extracting(MissionExecutionSnapshot.TaskExecution::state)
                .containsOnly(MissionTaskState.COMPLETED);
        assertThat(fourth.activeAttempts()).isEmpty();
        assertThat(runtime.uniqueStarts()).isEqualTo(3);
        assertThat(runtime.bindings.values())
                .extracting(MissionRuntimeAccess.TaskRunBinding::runId)
                .doesNotHaveDuplicates();
    }

    @Test
    void recoversTheStartBeforeBindCrashWindowWithTheSameRunAndDeduplicatesSettlement() {
        Path database = directory.resolve("bind-recovery.sqlite");
        AtomicInteger ids = new AtomicInteger();
        FakeRuntime runtime = new FakeRuntime();
        SqliteMissionStore base = store(database);
        MissionApplicationService service = service(base, ids);
        var created = service.create(create("create", "conversation", "Execute safely", List.of("done")));
        service.confirm(change("confirm", created.missionId(), created.version()));

        FaultingBindStore faulting = new FaultingBindStore(base);
        assertThatThrownBy(() -> coordinator(faulting, runtime, CLOCK).tick())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated bind crash");
        assertThat(base.activeAttempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.state()).isEqualTo(MissionTaskAttemptState.DISPATCH_PENDING);
            assertThat(attempt.runId()).isEmpty();
        });
        assertThat(runtime.uniqueStarts()).isEqualTo(1);

        Clock afterClaimTimeout = Clock.fixed(START.plusSeconds(31), ZoneOffset.UTC);
        coordinator(store(database), runtime, afterClaimTimeout).tick();
        MissionTaskAttempt bound = base.activeAttempts().getFirst();
        assertThat(bound.state()).isEqualTo(MissionTaskAttemptState.BOUND);
        assertThat(runtime.uniqueStarts()).isEqualTo(1);

        runtime.complete(bound, "done");
        coordinator(base, runtime, afterClaimTimeout).tick();
        base.settleCompleted(bound, "sha256:duplicate", "duplicate", START.plusSeconds(32));
        assertThat(eventCount(database, "MISSION_TASK_COMPLETED")).isEqualTo(1);
        assertThat(base.snapshot(created.missionId()).completedTasks()).isEqualTo(1);
    }

    @Test
    void failureInteractionRetryCancelAndOutcomeUnknownRemainExplicit() {
        Path database = directory.resolve("control.sqlite");
        AtomicInteger ids = new AtomicInteger();
        FakeRuntime runtime = new FakeRuntime();
        SqliteMissionStore store = store(database);
        MissionApplicationService service = service(store, ids);
        var created = service.create(create("create", "conversation", "Handle controls", List.of("done")));
        service.confirm(change("confirm", created.missionId(), created.version()));
        MissionExecutionCoordinator coordinator = coordinator(store, runtime, CLOCK);

        coordinator.tick();
        MissionTaskAttempt first = store.activeAttempts().getFirst();
        runtime.observe(first, MissionRuntimeAccess.TaskRunState.WAITING_USER, null, null);
        coordinator.tick();
        assertThat(store.missionState(created.missionId())).isEqualTo(MissionState.WAITING_USER);

        runtime.observe(first, MissionRuntimeAccess.TaskRunState.FAILED, null, "TRANSIENT_FAILURE");
        coordinator.tick();
        MissionTaskAttempt second = store.activeAttempts().getFirst();
        assertThat(second.attemptNo()).isEqualTo(2);
        runtime.observe(second, MissionRuntimeAccess.TaskRunState.FAILED, null, "TRANSIENT_FAILURE");
        coordinator.tick();
        assertThat(store.snapshot(created.missionId()).blockedTasks()).isEqualTo(1);
        assertThat(store.activeAttempts()).isEmpty();

        var blocked = service.find(created.missionId(), OWNER).orElseThrow();
        var retried = service.retry(new MissionApplicationService.RetryMissionTask(
                "retry", OWNER, created.missionId(), "task-1", blocked.version()));
        assertThat(retried.state()).isEqualTo(MissionState.RUNNING);
        coordinator.tick();
        MissionTaskAttempt third = store.activeAttempts().getFirst();
        assertThat(third.attemptNo()).isEqualTo(3);

        var beforeCancel = service.find(created.missionId(), OWNER).orElseThrow();
        var cancelled = service.cancel(change("cancel", created.missionId(), beforeCancel.version()));
        assertThat(cancelled.execution().tasks())
                .extracting(MissionExecutionSnapshot.TaskExecution::state)
                .containsOnly(MissionTaskState.CANCELLED);
        coordinator.tick();
        assertThat(runtime.cancelledRuns).contains(third.runId().orElseThrow());
        assertThat(store.activeAttempts()).isEmpty();

        var unknownCreated = service.create(
                create("create-unknown", "conversation-unknown", "Do not replay unknown work", List.of("safe")));
        service.confirm(change("confirm-unknown", unknownCreated.missionId(), unknownCreated.version()));
        coordinator.tick();
        MissionTaskAttempt unknown = store.activeAttempts().getFirst();
        runtime.observe(unknown, MissionRuntimeAccess.TaskRunState.OUTCOME_UNKNOWN, null, "UNKNOWN");
        coordinator.tick();
        int startsAfterUnknown = runtime.uniqueStarts();
        coordinator.tick();
        MissionExecutionSnapshot unknownExecution = store.snapshot(unknownCreated.missionId());
        assertThat(unknownExecution.blockedTasks()).isEqualTo(1);
        assertThat(unknownExecution.latestAttempt())
                .get()
                .extracting(MissionTaskAttempt::state)
                .isEqualTo(MissionTaskAttemptState.OUTCOME_UNKNOWN);
        assertThat(runtime.uniqueStarts()).isEqualTo(startsAfterUnknown);
    }

    private static void assertSingleBoundAttempt(SqliteMissionStore store, int expectedTaskOrdinal) {
        assertThat(store.activeAttempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.state()).isEqualTo(MissionTaskAttemptState.BOUND);
            assertThat(attempt.taskId()).isEqualTo("task-" + expectedTaskOrdinal);
        });
    }

    private static MissionExecutionCoordinator coordinator(
            MissionExecutionStore store, MissionRuntimeAccess runtime, Clock clock) {
        return new MissionExecutionCoordinator(store, runtime, clock, "test-dispatcher");
    }

    private static MissionApplicationService service(SqliteMissionStore store, AtomicInteger ids) {
        return new MissionApplicationService(
                store,
                store,
                new DeterministicMissionPlanner(),
                MissionPlanValidator.phaseOne(),
                () -> "mission-" + ids.incrementAndGet(),
                CLOCK,
                store);
    }

    private static MissionApplicationService.CreateMission create(
            String key, String conversationId, String objective, List<String> criteria) {
        return new MissionApplicationService.CreateMission(
                key, OWNER, conversationId, objective, criteria, MissionConstraints.DEFAULT);
    }

    private static MissionApplicationService.ChangeMission change(String key, String missionId, long version) {
        return new MissionApplicationService.ChangeMission(key, OWNER, missionId, version);
    }

    private static SqliteMissionStore store(Path database) {
        return new SqliteMissionStore(database, new ObjectMapper());
    }

    private static int eventCount(Path database, String eventType) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
                var statement =
                        connection.prepareStatement("SELECT COUNT(*) FROM personal_mission_event WHERE event_type=?")) {
            statement.setString(1, eventType);
            try (var result = statement.executeQuery()) {
                return result.getInt(1);
            }
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class FakeRuntime implements MissionRuntimeAccess {
        private final Map<String, TaskRunBinding> bindings = new LinkedHashMap<>();
        private final Map<String, TaskRunObservation> observations = new HashMap<>();
        private final Set<String> cancelledRuns = new java.util.HashSet<>();

        @Override
        public PlannerRunResult runPlanner(
                io.haifa.agent.personalassistant.application.mission.MissionPlanner.PlanningRequest request) {
            throw new AssertionError("planner is not used");
        }

        @Override
        public TaskRunBinding startTask(MissionDispatchIntent intent) {
            return bindings.computeIfAbsent(intent.dispatchKey(), ignored -> {
                String suffix = Integer.toString(bindings.size() + 1);
                TaskRunBinding binding = new TaskRunBinding("session-" + suffix, "run-" + suffix);
                observations.put(
                        binding.runId(),
                        new TaskRunObservation(
                                binding.runId(), TaskRunState.ACTIVE, Optional.empty(), Optional.empty()));
                return binding;
            });
        }

        @Override
        public TaskRunObservation observeTask(String runId) {
            return observations.getOrDefault(
                    runId,
                    new TaskRunObservation(
                            runId, TaskRunState.OUTCOME_UNKNOWN, Optional.empty(), Optional.of("MISSING")));
        }

        @Override
        public void cancelTask(String runId) {
            cancelledRuns.add(runId);
            observations.put(
                    runId, new TaskRunObservation(runId, TaskRunState.CANCELLED, Optional.empty(), Optional.empty()));
        }

        void complete(MissionTaskAttempt attempt, String result) {
            observe(attempt, TaskRunState.COMPLETED, result, null);
        }

        void observe(MissionTaskAttempt attempt, TaskRunState state, String result, String failureCode) {
            String runId = attempt.runId().orElseThrow();
            observations.put(
                    runId,
                    new TaskRunObservation(
                            runId, state, Optional.ofNullable(result), Optional.ofNullable(failureCode)));
        }

        int uniqueStarts() {
            return bindings.size();
        }
    }

    private static final class FaultingBindStore implements MissionExecutionStore {
        private final MissionExecutionStore delegate;
        private final AtomicBoolean fail = new AtomicBoolean(true);

        private FaultingBindStore(MissionExecutionStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<MissionDispatchIntent> prepareAndClaimNext(String id, Instant now, Instant stale) {
            return delegate.prepareAndClaimNext(id, now, stale);
        }

        @Override
        public void bind(MissionDispatchIntent intent, String sessionId, String runId, Instant now) {
            if (fail.compareAndSet(true, false)) throw new IllegalStateException("simulated bind crash");
            delegate.bind(intent, sessionId, runId, now);
        }

        @Override
        public void failDispatch(MissionDispatchIntent intent, String code, boolean retryable, Instant now) {
            delegate.failDispatch(intent, code, retryable, now);
        }

        @Override
        public List<MissionTaskAttempt> activeAttempts() {
            return delegate.activeAttempts();
        }

        @Override
        public MissionState missionState(String missionId) {
            return delegate.missionState(missionId);
        }

        @Override
        public void waitingForUser(MissionTaskAttempt attempt, Instant now) {
            delegate.waitingForUser(attempt, now);
        }

        @Override
        public void settleCompleted(MissionTaskAttempt attempt, String digest, String result, Instant now) {
            delegate.settleCompleted(attempt, digest, result, now);
        }

        @Override
        public void settleFailed(MissionTaskAttempt attempt, String code, boolean retryable, Instant now) {
            delegate.settleFailed(attempt, code, retryable, now);
        }

        @Override
        public void settleCancelled(MissionTaskAttempt attempt, Instant now) {
            delegate.settleCancelled(attempt, now);
        }

        @Override
        public MissionExecutionSnapshot snapshot(String missionId) {
            return delegate.snapshot(missionId);
        }

        @Override
        public void retryBlocked(String missionId, String owner, String taskId, Instant now) {
            delegate.retryBlocked(missionId, owner, taskId, now);
        }
    }
}

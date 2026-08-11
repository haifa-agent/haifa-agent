package io.haifa.agent.personalassistant.server.mission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.personalassistant.application.mission.DeterministicMissionPlanner;
import io.haifa.agent.personalassistant.application.mission.MissionApplicationService;
import io.haifa.agent.personalassistant.application.mission.MissionConstraints;
import io.haifa.agent.personalassistant.application.mission.MissionException;
import io.haifa.agent.personalassistant.application.mission.MissionListCursor;
import io.haifa.agent.personalassistant.application.mission.MissionPlanValidator;
import io.haifa.agent.personalassistant.application.mission.MissionPublishedResult;
import io.haifa.agent.personalassistant.application.mission.MissionRuntimeAccess;
import io.haifa.agent.personalassistant.application.mission.MissionSnapshot;
import io.haifa.agent.personalassistant.application.mission.MissionState;
import io.haifa.agent.personalassistant.application.mission.MissionUsage;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteMissionStoreTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path directory;

    @Test
    void migratesPersistsRestartsAndKeepsCommandsIdempotent() {
        Path database = directory.resolve("personal.sqlite");
        AtomicInteger ids = new AtomicInteger();
        SqliteMissionStore firstStore = new SqliteMissionStore(database, new ObjectMapper());
        MissionApplicationService first = service(firstStore, ids);
        var command = new MissionApplicationService.CreateMission(
                "create-1",
                "local/public-user",
                "conversation-1",
                "Prepare a release brief",
                List.of("Architecture is covered", "Tests are covered"),
                MissionConstraints.DEFAULT);

        MissionSnapshot created = first.create(command);
        assertThat(firstStore.schemaVersion()).isEqualTo(6);

        SqliteMissionStore restartedStore = new SqliteMissionStore(database, new ObjectMapper());
        MissionApplicationService restarted = service(restartedStore, ids);
        MissionSnapshot restored =
                restarted.find(created.missionId(), "local/public-user").orElseThrow();
        assertThat(restored).isEqualTo(created);
        assertThat(restarted.create(command).missionId()).isEqualTo(created.missionId());

        MissionSnapshot confirmed = restarted.confirm(new MissionApplicationService.ChangeMission(
                "confirm-1", "local/public-user", created.missionId(), restored.version()));
        assertThat(confirmed.state()).isEqualTo(MissionState.RUNNING);
        SqliteMissionStore confirmedRestart = new SqliteMissionStore(database, new ObjectMapper());
        assertThat(confirmedRestart
                        .execute(() -> confirmedRestart.find(created.missionId(), "local/public-user"))
                        .orElseThrow()
                        .snapshot()
                        .state())
                .isEqualTo(MissionState.RUNNING);

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
            assertThat(connection
                            .createStatement()
                            .executeUpdate(
                                    "UPDATE personal_mission_task SET state='READY', version=version+1 WHERE mission_id='"
                                            + created.missionId() + "'"))
                    .isPositive();
            assertThatThrownBy(() -> connection
                            .createStatement()
                            .executeUpdate("UPDATE personal_mission_task SET title='mutated' WHERE mission_id='"
                                    + created.missionId() + "'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("MISSION_PLAN_FROZEN");
            assertThatThrownBy(() -> connection
                            .createStatement()
                            .executeUpdate("DELETE FROM personal_mission_task_dependency WHERE mission_id='"
                                    + created.missionId() + "'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("MISSION_PLAN_FROZEN");
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void enforcesOneActiveMissionAtDatabaseBoundary() {
        SqliteMissionStore store = new SqliteMissionStore(directory.resolve("unique.sqlite"), new ObjectMapper());
        MissionApplicationService service = service(store, new AtomicInteger());
        service.create(create("create-1", "conversation-1", "First objective"));

        assertThatThrownBy(() -> service.create(create("create-2", "conversation-1", "Second objective")))
                .isInstanceOf(MissionException.class)
                .extracting(value -> ((MissionException) value).code())
                .isEqualTo("MISSION_ACTIVE_EXISTS");
    }

    @Test
    void concurrentClientsCreateAtMostOneActiveMission() throws Exception {
        SqliteMissionStore store = new SqliteMissionStore(directory.resolve("concurrent.sqlite"), new ObjectMapper());
        MissionApplicationService service = service(store, new AtomicInteger());
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() ->
                    createConcurrently(start, service, create("concurrent-1", "conversation-1", "First objective")));
            var second = executor.submit(() ->
                    createConcurrently(start, service, create("concurrent-2", "conversation-1", "Second objective")));
            start.countDown();
            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder("CREATED", "MISSION_ACTIVE_EXISTS");
        }
        assertThat(service.list("local/public-user", java.util.Optional.of("conversation-1"), 10))
                .hasSize(1);
    }

    @Test
    void usesUpdatedTimeAndMissionIdForStableKeysetPagination() {
        SqliteMissionStore store = new SqliteMissionStore(directory.resolve("pagination.sqlite"), new ObjectMapper());
        MissionApplicationService service = service(store, new AtomicInteger());
        MissionSnapshot first = service.create(create("create-1", "conversation-1", "First objective"));
        MissionSnapshot second = service.create(create("create-2", "conversation-2", "Second objective"));
        MissionSnapshot third = service.create(create("create-3", "conversation-3", "Third objective"));

        List<MissionSnapshot> firstPage =
                service.list("local/public-user", java.util.Optional.empty(), java.util.Optional.empty(), 2);
        MissionSnapshot boundary = firstPage.getLast();
        List<MissionSnapshot> secondPage = service.list(
                "local/public-user",
                java.util.Optional.empty(),
                java.util.Optional.of(new MissionListCursor(boundary.updatedAt(), boundary.missionId())),
                2);

        assertThat(firstPage)
                .extracting(MissionSnapshot::missionId)
                .containsExactly(third.missionId(), second.missionId());
        assertThat(secondPage).extracting(MissionSnapshot::missionId).containsExactly(first.missionId());
    }

    @Test
    void dispatchIntentRecoversAfterStaleClaimAndSettlesOneTask() {
        SqliteMissionStore store = new SqliteMissionStore(directory.resolve("dispatch.sqlite"), new ObjectMapper());
        store.registerDispatcher("process", "instance", CLOCK.instant());
        AtomicInteger ids = new AtomicInteger();
        MissionApplicationService service = new MissionApplicationService(
                store,
                store,
                new DeterministicMissionPlanner(),
                MissionPlanValidator.phaseOne(),
                () -> "mission-" + ids.incrementAndGet(),
                CLOCK,
                store);
        MissionSnapshot created = service.create(create("create-1", "conversation-1", "Execute one durable task"));
        MissionSnapshot confirmed = service.confirm(new MissionApplicationService.ChangeMission(
                "confirm-1", "local/public-user", created.missionId(), created.version()));

        var claimed = store.prepareAndClaimNext(
                        "dispatcher-a", CLOCK.instant(), CLOCK.instant().minusSeconds(30))
                .orElseThrow();
        assertThat(store.prepareAndClaimNext(
                        "dispatcher-b", CLOCK.instant(), CLOCK.instant().minusSeconds(30)))
                .isEmpty();
        var reclaimed = store.prepareAndClaimNext(
                        "dispatcher-b",
                        CLOCK.instant().plusSeconds(31),
                        CLOCK.instant().plusSeconds(1))
                .orElseThrow();
        assertThat(reclaimed.dispatchKey()).isEqualTo(claimed.dispatchKey());

        store.bind(reclaimed, "session-1", "run-1", CLOCK.instant().plusSeconds(31));
        var attempt = store.activeAttempts().getFirst();
        store.settleCompleted(attempt, "sha256:result", "done", CLOCK.instant().plusSeconds(32));
        var execution = store.snapshot(confirmed.missionId());
        assertThat(execution.completedTasks()).isEqualTo(1);
        assertThat(execution.allTasksSettled()).isTrue();
        assertThat(store.activeAttempts()).isEmpty();
    }

    @Test
    void freezesDependencyResultsInDispatchInputAndKeepsRetryDigestStable() {
        SqliteMissionStore store = new SqliteMissionStore(
                directory.resolve("dependency-input.sqlite"), new ObjectMapper(), 2, 3, 200_000, 100);
        store.registerDispatcher("process", "instance", CLOCK.instant());
        MissionApplicationService service = new MissionApplicationService(
                store,
                store,
                new DeterministicMissionPlanner(),
                MissionPlanValidator.phaseOne(),
                () -> "mission-dependency-input",
                CLOCK,
                store);
        MissionSnapshot created = service.create(new MissionApplicationService.CreateMission(
                "create-dependency-input",
                "local/public-user",
                "conversation-dependency-input",
                "Research then integrate the result",
                List.of("research", "integrate"),
                MissionConstraints.DEFAULT));
        service.confirm(new MissionApplicationService.ChangeMission(
                "confirm-dependency-input", "local/public-user", created.missionId(), created.version()));

        var first = store.prepareAndClaimNext("dispatcher", CLOCK.instant(), CLOCK.instant())
                .orElseThrow();
        store.bind(first, "session-1", "run-1", CLOCK.instant());
        String completedResult = "verified dependency result";
        String completedDigest = "sha256:" + "a".repeat(64);
        store.settleCompleted(store.activeAttempts().getFirst(), completedDigest, completedResult, CLOCK.instant());

        var dependent = store.prepareAndClaimNext("dispatcher", CLOCK.instant(), CLOCK.instant())
                .orElseThrow();
        assertThat(dependent.runInput().missionObjective()).isEqualTo("Research then integrate the result");
        assertThat(dependent.runInput().executionProfileId())
                .isEqualTo(
                        io.haifa.agent.personalassistant.application.mission.MissionTaskRunInput
                                .DEPENDENCY_AWARE_RESEARCH_PROFILE);
        assertThat(dependent.runInput().dependencyResults()).singleElement().satisfies(value -> {
            assertThat(value.taskId()).isEqualTo("task-1");
            assertThat(value.resultDigest()).isEqualTo(completedDigest);
            assertThat(value.resultJson()).isEqualTo(completedResult);
        });

        store.bind(dependent, "session-2", "run-2", CLOCK.instant());
        store.settleFailed(store.activeAttempts().getFirst(), "TRANSIENT", true, CLOCK.instant());
        var retry = store.prepareAndClaimNext("dispatcher", CLOCK.instant(), CLOCK.instant())
                .orElseThrow();
        assertThat(retry.payloadDigest()).isEqualTo(dependent.payloadDigest());
        assertThat(retry.runInput()).isEqualTo(dependent.runInput());
    }

    @Test
    void defaultStoreRequiresExplicitUserRetryAfterARetryableFailure() {
        SqliteMissionStore store =
                new SqliteMissionStore(directory.resolve("explicit-retry.sqlite"), new ObjectMapper());
        store.registerDispatcher("process", "instance", CLOCK.instant());
        MissionApplicationService service = new MissionApplicationService(
                store,
                store,
                new DeterministicMissionPlanner(),
                MissionPlanValidator.phaseOne(),
                () -> "mission-explicit-retry",
                CLOCK,
                store);
        MissionSnapshot created = service.create(new MissionApplicationService.CreateMission(
                "create-explicit-retry",
                "local/public-user",
                "conversation-explicit-retry",
                "Require explicit retry authority",
                List.of("complete once"),
                MissionConstraints.DEFAULT));
        service.confirm(new MissionApplicationService.ChangeMission(
                "confirm-explicit-retry", "local/public-user", created.missionId(), created.version()));

        var intent = store.prepareAndClaimNext("dispatcher", CLOCK.instant(), CLOCK.instant())
                .orElseThrow();
        store.bind(intent, "session-1", "run-1", CLOCK.instant());
        store.settleFailed(store.activeAttempts().getFirst(), "MODEL_RESPONSE_INVALID", true, CLOCK.instant());

        assertThat(store.prepareAndClaimNext("dispatcher", CLOCK.instant(), CLOCK.instant()))
                .isEmpty();
        assertThat(store.snapshot(created.missionId()).blockedTasks()).isEqualTo(1);
        assertThat(store.missionState(created.missionId())).isEqualTo(MissionState.WAITING_USER);
    }

    @Test
    void exhaustedUserRetryCancelsDependentsAndSettlesPartialSynthesis() {
        SqliteMissionStore store =
                new SqliteMissionStore(directory.resolve("partial.sqlite"), new ObjectMapper(), 2, 3, 200_000, 100);
        store.registerDispatcher("process", "instance", CLOCK.instant());
        MissionApplicationService service = new MissionApplicationService(
                store,
                store,
                new DeterministicMissionPlanner(),
                MissionPlanValidator.phaseOne(),
                () -> "mission-partial",
                CLOCK,
                store);
        MissionSnapshot created = service.create(new MissionApplicationService.CreateMission(
                "create-partial",
                "local/public-user",
                "conversation-partial",
                "Produce a bounded partial result",
                List.of("first", "dependent second"),
                MissionConstraints.DEFAULT));
        service.confirm(new MissionApplicationService.ChangeMission(
                "confirm-partial", "local/public-user", created.missionId(), created.version()));

        var firstIntent = store.prepareAndClaimNext("dispatcher", CLOCK.instant(), CLOCK.instant())
                .orElseThrow();
        store.bind(firstIntent, "session-1", "run-1", CLOCK.instant());
        store.settleFailed(store.activeAttempts().getFirst(), "TRANSIENT", true, CLOCK.instant());
        var secondIntent = store.prepareAndClaimNext("dispatcher", CLOCK.instant(), CLOCK.instant())
                .orElseThrow();
        store.bind(secondIntent, "session-2", "run-2", CLOCK.instant());
        store.settleFailed(store.activeAttempts().getFirst(), "TRANSIENT", true, CLOCK.instant());

        MissionSnapshot blocked =
                service.find(created.missionId(), "local/public-user").orElseThrow();
        service.retry(new MissionApplicationService.RetryMissionTask(
                "retry-partial", "local/public-user", created.missionId(), "task-1", blocked.version()));
        var thirdIntent = store.prepareAndClaimNext("dispatcher", CLOCK.instant(), CLOCK.instant())
                .orElseThrow();
        store.bind(thirdIntent, "session-3", "run-3", CLOCK.instant());
        store.settleFailed(store.activeAttempts().getFirst(), "FINAL_FAILURE", false, CLOCK.instant());

        var synthesis = store.claimSynthesis(CLOCK.instant()).orElseThrow();
        assertThat(synthesis.taskResults()).isEmpty();
        assertThat(synthesis.failedItems())
                .containsExactly("task-1:BLOCKED:FINAL_FAILURE", "task-2:CANCELLED:MISSION_DEPENDENCY_BLOCKED");
        store.settleSynthesis(
                synthesis,
                new MissionRuntimeAccess.SynthesisRunResult("synthesis-session", "synthesis-run", "{}"),
                new MissionPublishedResult(
                        "artifact-final", List.of("artifact-final"), List.of(), "{}", "Partial result", "PARTIAL"),
                CLOCK.instant());

        assertThat(store.missionState(created.missionId())).isEqualTo(MissionState.PARTIALLY_COMPLETED);
        assertThat(store.snapshot(created.missionId()).tasks())
                .extracting(
                        io.haifa.agent.personalassistant.application.mission.MissionExecutionSnapshot.TaskExecution
                                ::state)
                .containsExactly(
                        io.haifa.agent.personalassistant.application.mission.MissionTaskState.BLOCKED,
                        io.haifa.agent.personalassistant.application.mission.MissionTaskState.CANCELLED);
    }

    @Test
    void countsAuthoritativeUsageOnceAndStopsNewWorkAtBudget() {
        Path database = directory.resolve("budget.sqlite");
        SqliteMissionStore store = new SqliteMissionStore(database, new ObjectMapper(), 2, 3, 10, 10);
        store.registerDispatcher("process", "instance", CLOCK.instant());
        MissionApplicationService service = service(store, new AtomicInteger());
        MissionSnapshot created = service.create(new MissionApplicationService.CreateMission(
                "create-budget",
                "local/public-user",
                "conversation-budget",
                "Produce a bounded result",
                List.of("first", "second"),
                MissionConstraints.DEFAULT));
        service.confirm(new MissionApplicationService.ChangeMission(
                "confirm-budget", "local/public-user", created.missionId(), created.version()));

        var intent = store.prepareAndClaimNext("dispatcher", CLOCK.instant(), CLOCK.instant())
                .orElseThrow();
        store.bind(intent, "session-budget", "run-budget", CLOCK.instant());
        var attempt = store.activeAttempts().getFirst();
        MissionUsage usage = new MissionUsage(10, 1, 2);
        store.settleCompleted(attempt, "sha256:result", "done", usage, CLOCK.instant());
        store.settleCompleted(attempt, "sha256:result", "done", usage, CLOCK.instant());

        assertThat(store.prepareAndClaimNext("dispatcher", CLOCK.instant(), CLOCK.instant()))
                .isEmpty();
        var operations = store.operationalSnapshot(CLOCK.instant());
        assertThat(operations.modelTokens()).isEqualTo(10);
        assertThat(operations.modelCalls()).isEqualTo(1);
        assertThat(operations.toolCalls()).isEqualTo(2);
        assertThat(operations.budgetExhaustedTasks()).isEqualTo(1);
        assertThat(store.claimSynthesis(CLOCK.instant()).orElseThrow().failedItems())
                .containsExactly("task-2:BLOCKED:MISSION_BUDGET_EXHAUSTED");
    }

    @Test
    void deadlineCancelsRemainingWorkButStillAllowsPartialSynthesis() throws SQLException {
        Path database = directory.resolve("deadline.sqlite");
        SqliteMissionStore store = new SqliteMissionStore(database, new ObjectMapper());
        store.registerDispatcher("process", "instance", CLOCK.instant());
        MissionApplicationService service = service(store, new AtomicInteger());
        MissionSnapshot created = service.create(new MissionApplicationService.CreateMission(
                "create-deadline",
                "local/public-user",
                "conversation-deadline",
                "Produce the evidence available before timeout",
                List.of("first", "second"),
                MissionConstraints.DEFAULT));
        service.confirm(new MissionApplicationService.ChangeMission(
                "confirm-deadline", "local/public-user", created.missionId(), created.version()));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
                var statement = connection.prepareStatement(
                        "UPDATE personal_mission SET deadline_at_ms=? WHERE mission_id=?")) {
            statement.setLong(1, CLOCK.instant().toEpochMilli());
            statement.setString(2, created.missionId());
            statement.executeUpdate();
            try (var blocked = connection.prepareStatement(
                    "UPDATE personal_mission_task SET state='BLOCKED',block_code='MODEL_REQUEST_INVALID',"
                            + "latest_attempt_no=1 WHERE mission_id=? AND ordinal=1")) {
                blocked.setString(1, created.missionId());
                blocked.executeUpdate();
            }
        }

        assertThat(store.prepareAndClaimNext("dispatcher", CLOCK.instant(), CLOCK.instant()))
                .isEmpty();
        var synthesis = store.claimSynthesis(CLOCK.instant()).orElseThrow();
        assertThat(synthesis.failedItems())
                .containsExactly(
                        "task-1:CANCELLED:MISSION_DEADLINE_EXCEEDED", "task-2:CANCELLED:MISSION_DEADLINE_EXCEEDED");
        store.settleSynthesis(
                synthesis,
                new MissionRuntimeAccess.SynthesisRunResult("session", "run", "{}"),
                new MissionPublishedResult("artifact", List.of("artifact"), List.of(), "{}", "Partial", "PARTIAL"),
                CLOCK.instant());
        assertThat(store.missionState(created.missionId())).isEqualTo(MissionState.PARTIALLY_COMPLETED);
    }

    @Test
    void rejectsDatabaseFromNewerPersonalSchema() throws SQLException {
        Path database = directory.resolve("newer.sqlite");
        new SqliteMissionStore(database, new ObjectMapper());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
                var statement = connection.prepareStatement(
                        "INSERT INTO personal_schema_history(version,checksum,installed_at_ms) VALUES (?,?,?)")) {
            statement.setInt(1, 7);
            statement.setString(2, "future");
            statement.setLong(3, CLOCK.instant().toEpochMilli());
            statement.executeUpdate();
        }
        assertThatThrownBy(() -> new SqliteMissionStore(database, new ObjectMapper()))
                .isInstanceOf(MissionException.class)
                .extracting(value -> ((MissionException) value).code())
                .isEqualTo("MISSION_SCHEMA_NEWER_THAN_APPLICATION");
    }

    private static String createConcurrently(
            CountDownLatch start, MissionApplicationService service, MissionApplicationService.CreateMission command)
            throws InterruptedException {
        start.await();
        try {
            service.create(command);
            return "CREATED";
        } catch (MissionException exception) {
            return exception.code();
        }
    }

    private static MissionApplicationService service(SqliteMissionStore store, AtomicInteger ids) {
        return new MissionApplicationService(
                store,
                store,
                new DeterministicMissionPlanner(),
                MissionPlanValidator.phaseOne(),
                () -> "mission-" + ids.incrementAndGet(),
                CLOCK);
    }

    private static MissionApplicationService.CreateMission create(String key, String conversationId, String objective) {
        return new MissionApplicationService.CreateMission(
                key,
                "local/public-user",
                conversationId,
                objective,
                List.of("Result is complete"),
                MissionConstraints.DEFAULT);
    }
}

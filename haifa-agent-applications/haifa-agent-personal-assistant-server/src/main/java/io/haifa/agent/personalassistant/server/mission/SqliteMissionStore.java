package io.haifa.agent.personalassistant.server.mission;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.personalassistant.application.mission.MissionCommandBinding;
import io.haifa.agent.personalassistant.application.mission.MissionCommandReservation;
import io.haifa.agent.personalassistant.application.mission.MissionConstraints;
import io.haifa.agent.personalassistant.application.mission.MissionDispatchIntent;
import io.haifa.agent.personalassistant.application.mission.MissionException;
import io.haifa.agent.personalassistant.application.mission.MissionExecutionSnapshot;
import io.haifa.agent.personalassistant.application.mission.MissionExecutionStore;
import io.haifa.agent.personalassistant.application.mission.MissionListCursor;
import io.haifa.agent.personalassistant.application.mission.MissionPlanRevision;
import io.haifa.agent.personalassistant.application.mission.MissionPublishedResult;
import io.haifa.agent.personalassistant.application.mission.MissionRuntimeAccess;
import io.haifa.agent.personalassistant.application.mission.MissionState;
import io.haifa.agent.personalassistant.application.mission.MissionStore;
import io.haifa.agent.personalassistant.application.mission.MissionSynthesisIntent;
import io.haifa.agent.personalassistant.application.mission.MissionTask;
import io.haifa.agent.personalassistant.application.mission.MissionTaskAttempt;
import io.haifa.agent.personalassistant.application.mission.MissionTaskAttemptState;
import io.haifa.agent.personalassistant.application.mission.MissionTaskState;
import io.haifa.agent.personalassistant.application.mission.MissionUnitOfWork;
import io.haifa.agent.personalassistant.application.mission.MissionUsage;
import io.haifa.agent.personalassistant.application.mission.PersonalMission;
import io.haifa.agent.store.sqlite.migration.SqlScriptParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Product-owned SQLite migration, Store and UoW. It deliberately does not modify public Runtime mappings. */
public final class SqliteMissionStore implements MissionStore, MissionUnitOfWork, MissionExecutionStore {
    private static final int SCHEMA_VERSION = 6;
    private static final String MIGRATION =
            """
            CREATE TABLE personal_mission (
                mission_id TEXT PRIMARY KEY NOT NULL,
                conversation_id TEXT NOT NULL,
                owner_scope TEXT NOT NULL,
                objective TEXT NOT NULL,
                acceptance_json TEXT NOT NULL,
                constraints_json TEXT NOT NULL,
                selected_skill_id TEXT,
                state TEXT NOT NULL,
                active_plan_revision_no INTEGER,
                confirmed_plan_revision_no INTEGER,
                synthesis_session_id TEXT,
                synthesis_run_id TEXT,
                final_artifact_id TEXT,
                final_message_key TEXT,
                failure_code TEXT,
                version INTEGER NOT NULL,
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                confirmed_at_ms INTEGER,
                started_at_ms INTEGER,
                finished_at_ms INTEGER,
                deadline_at_ms INTEGER NOT NULL,
                CHECK(version >= 0),
                CHECK(created_at_ms >= 0 AND updated_at_ms >= created_at_ms),
                CHECK(state IN ('PLANNING','WAITING_CONFIRMATION','RUNNING','WAITING_USER','SYNTHESIZING','COMPLETED','PARTIALLY_COMPLETED','FAILED','CANCELLED'))
            );
            CREATE UNIQUE INDEX uq_personal_mission_active_conversation
                ON personal_mission(conversation_id, owner_scope)
                WHERE state NOT IN ('COMPLETED','PARTIALLY_COMPLETED','FAILED','CANCELLED');
            CREATE INDEX ix_personal_mission_list
                ON personal_mission(owner_scope, updated_at_ms DESC, mission_id DESC);

            CREATE TABLE personal_mission_plan_revision (
                mission_id TEXT NOT NULL REFERENCES personal_mission(mission_id) ON DELETE RESTRICT,
                revision_no INTEGER NOT NULL,
                schema_id TEXT NOT NULL,
                schema_version TEXT NOT NULL,
                plan_json TEXT NOT NULL,
                plan_digest TEXT NOT NULL,
                planner_session_id TEXT,
                planner_run_id TEXT,
                created_at_ms INTEGER NOT NULL,
                confirmed_at_ms INTEGER,
                PRIMARY KEY(mission_id, revision_no),
                CHECK(revision_no >= 1)
            );
            CREATE TRIGGER personal_mission_confirmed_plan_no_update
            BEFORE UPDATE ON personal_mission_plan_revision
            WHEN OLD.confirmed_at_ms IS NOT NULL
            BEGIN SELECT RAISE(ABORT, 'MISSION_PLAN_FROZEN'); END;
            CREATE TRIGGER personal_mission_confirmed_plan_no_delete
            BEFORE DELETE ON personal_mission_plan_revision
            WHEN OLD.confirmed_at_ms IS NOT NULL
            BEGIN SELECT RAISE(ABORT, 'MISSION_PLAN_FROZEN'); END;

            CREATE TABLE personal_mission_task (
                mission_id TEXT NOT NULL REFERENCES personal_mission(mission_id) ON DELETE RESTRICT,
                task_id TEXT NOT NULL,
                plan_revision_no INTEGER NOT NULL,
                ordinal INTEGER NOT NULL,
                title TEXT NOT NULL,
                objective TEXT NOT NULL,
                acceptance_json TEXT NOT NULL,
                task_type TEXT NOT NULL,
                skill_ids_json TEXT NOT NULL,
                result_schema_id TEXT NOT NULL,
                result_schema_version TEXT NOT NULL,
                state TEXT NOT NULL,
                latest_attempt_no INTEGER NOT NULL DEFAULT 0,
                result_json TEXT,
                result_digest TEXT,
                block_code TEXT,
                version INTEGER NOT NULL DEFAULT 0,
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                PRIMARY KEY(mission_id, task_id),
                UNIQUE(mission_id, plan_revision_no, ordinal),
                CHECK(ordinal >= 1 AND latest_attempt_no >= 0 AND version >= 0),
                CHECK(task_type IN ('GENERAL','RESEARCH')),
                CHECK(state IN ('PLANNED','WAITING_DEPENDENCY','READY','COMPLETED','BLOCKED','CANCELLED'))
            );
            CREATE TABLE personal_mission_task_dependency (
                mission_id TEXT NOT NULL,
                task_id TEXT NOT NULL,
                depends_on_task_id TEXT NOT NULL,
                PRIMARY KEY(mission_id, task_id, depends_on_task_id),
                FOREIGN KEY(mission_id, task_id) REFERENCES personal_mission_task(mission_id, task_id) ON DELETE RESTRICT,
                FOREIGN KEY(mission_id, depends_on_task_id) REFERENCES personal_mission_task(mission_id, task_id) ON DELETE RESTRICT,
                CHECK(task_id <> depends_on_task_id)
            );
            CREATE TRIGGER personal_mission_confirmed_task_no_update
            BEFORE UPDATE ON personal_mission_task
            WHEN EXISTS (SELECT 1 FROM personal_mission m WHERE m.mission_id=OLD.mission_id AND m.confirmed_plan_revision_no IS NOT NULL)
              AND (NEW.mission_id <> OLD.mission_id
                OR NEW.task_id <> OLD.task_id
                OR NEW.plan_revision_no <> OLD.plan_revision_no
                OR NEW.ordinal <> OLD.ordinal
                OR NEW.title <> OLD.title
                OR NEW.objective <> OLD.objective
                OR NEW.acceptance_json <> OLD.acceptance_json
                OR NEW.task_type <> OLD.task_type
                OR NEW.skill_ids_json <> OLD.skill_ids_json
                OR NEW.result_schema_id <> OLD.result_schema_id
                OR NEW.result_schema_version <> OLD.result_schema_version)
            BEGIN SELECT RAISE(ABORT, 'MISSION_PLAN_FROZEN'); END;
            CREATE TRIGGER personal_mission_confirmed_task_no_delete
            BEFORE DELETE ON personal_mission_task
            WHEN EXISTS (SELECT 1 FROM personal_mission m WHERE m.mission_id=OLD.mission_id AND m.confirmed_plan_revision_no IS NOT NULL)
            BEGIN SELECT RAISE(ABORT, 'MISSION_PLAN_FROZEN'); END;
            CREATE TRIGGER personal_mission_confirmed_dependency_no_insert
            BEFORE INSERT ON personal_mission_task_dependency
            WHEN EXISTS (SELECT 1 FROM personal_mission m WHERE m.mission_id=NEW.mission_id AND m.confirmed_plan_revision_no IS NOT NULL)
            BEGIN SELECT RAISE(ABORT, 'MISSION_PLAN_FROZEN'); END;
            CREATE TRIGGER personal_mission_confirmed_dependency_no_update
            BEFORE UPDATE ON personal_mission_task_dependency
            WHEN EXISTS (SELECT 1 FROM personal_mission m WHERE m.mission_id=OLD.mission_id AND m.confirmed_plan_revision_no IS NOT NULL)
            BEGIN SELECT RAISE(ABORT, 'MISSION_PLAN_FROZEN'); END;
            CREATE TRIGGER personal_mission_confirmed_dependency_no_delete
            BEFORE DELETE ON personal_mission_task_dependency
            WHEN EXISTS (SELECT 1 FROM personal_mission m WHERE m.mission_id=OLD.mission_id AND m.confirmed_plan_revision_no IS NOT NULL)
            BEGIN SELECT RAISE(ABORT, 'MISSION_PLAN_FROZEN'); END;

            CREATE TABLE personal_mission_task_attempt (
                mission_id TEXT NOT NULL,
                task_id TEXT NOT NULL,
                attempt_no INTEGER NOT NULL,
                attempt_kind TEXT NOT NULL,
                dispatch_key TEXT NOT NULL UNIQUE,
                dispatch_payload_digest TEXT NOT NULL,
                state TEXT NOT NULL,
                session_id TEXT UNIQUE,
                run_id TEXT UNIQUE,
                result_digest TEXT,
                failure_code TEXT,
                version INTEGER NOT NULL,
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                started_at_ms INTEGER,
                settled_at_ms INTEGER,
                PRIMARY KEY(mission_id, task_id, attempt_no),
                FOREIGN KEY(mission_id, task_id) REFERENCES personal_mission_task(mission_id, task_id) ON DELETE RESTRICT,
                CHECK(attempt_no >= 1 AND version >= 0)
            );

            CREATE TABLE personal_mission_event (
                event_id INTEGER PRIMARY KEY AUTOINCREMENT,
                mission_id TEXT NOT NULL REFERENCES personal_mission(mission_id) ON DELETE RESTRICT,
                event_type TEXT NOT NULL,
                schema_version TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                created_at_ms INTEGER NOT NULL
            );
            CREATE INDEX ix_personal_mission_event ON personal_mission_event(mission_id, event_id);

            CREATE TABLE personal_mission_outbox (
                outbox_id TEXT PRIMARY KEY NOT NULL,
                mission_id TEXT NOT NULL REFERENCES personal_mission(mission_id) ON DELETE RESTRICT,
                task_id TEXT,
                attempt_no INTEGER,
                intent_type TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                payload_digest TEXT NOT NULL,
                state TEXT NOT NULL,
                available_at_ms INTEGER NOT NULL,
                claimed_at_ms INTEGER,
                claim_owner TEXT,
                completed_at_ms INTEGER,
                created_at_ms INTEGER NOT NULL
            );
            CREATE INDEX ix_personal_mission_outbox_fifo
                ON personal_mission_outbox(state, available_at_ms, created_at_ms, outbox_id);

            CREATE TABLE personal_mission_command (
                owner_scope TEXT NOT NULL,
                operation TEXT NOT NULL,
                idempotency_key TEXT NOT NULL,
                request_digest TEXT NOT NULL,
                mission_id TEXT NOT NULL REFERENCES personal_mission(mission_id) DEFERRABLE INITIALLY DEFERRED,
                created_at_ms INTEGER NOT NULL,
                PRIMARY KEY(owner_scope, operation, idempotency_key)
            );
            CREATE TABLE personal_mission_dispatcher_owner (
                owner_name TEXT PRIMARY KEY NOT NULL CHECK(owner_name='pa-mission'),
                process_id TEXT NOT NULL,
                instance_id TEXT NOT NULL,
                started_at_ms INTEGER NOT NULL,
                heartbeat_at_ms INTEGER NOT NULL,
                schema_version INTEGER NOT NULL
            );
            """;
    private static final String MIGRATION_V2 =
            """
            CREATE UNIQUE INDEX uq_personal_mission_active_attempt_global
                ON personal_mission_task_attempt((1))
                WHERE state IN ('CREATED','DISPATCH_PENDING','BOUND','SETTLEMENT_PENDING');
            CREATE INDEX ix_personal_mission_task_ready_fifo
                ON personal_mission_task(state, updated_at_ms, mission_id, task_id);
            """;
    private static final String MIGRATION_V3 =
            """
            ALTER TABLE personal_mission ADD COLUMN mode TEXT NOT NULL DEFAULT 'STANDARD'
                CHECK(mode IN ('STANDARD','DEEP_RESEARCH'));
            ALTER TABLE personal_mission ADD COLUMN research_brief_json TEXT;
            """;
    private static final String MIGRATION_V4 =
            """
            ALTER TABLE personal_mission ADD COLUMN artifact_refs_json TEXT NOT NULL DEFAULT '[]';
            ALTER TABLE personal_mission ADD COLUMN sources_json TEXT NOT NULL DEFAULT '[]';
            ALTER TABLE personal_mission ADD COLUMN final_result_json TEXT;
            """;
    private static final String MIGRATION_V5 =
            """
            ALTER TABLE personal_mission ADD COLUMN selected_skill_binding TEXT;
            """;
    private static final String MIGRATION_V6 =
            """
            ALTER TABLE personal_mission ADD COLUMN usage_model_tokens INTEGER NOT NULL DEFAULT 0
                CHECK(usage_model_tokens >= 0);
            ALTER TABLE personal_mission ADD COLUMN usage_model_calls INTEGER NOT NULL DEFAULT 0
                CHECK(usage_model_calls >= 0);
            ALTER TABLE personal_mission ADD COLUMN usage_tool_calls INTEGER NOT NULL DEFAULT 0
                CHECK(usage_tool_calls >= 0);
            """;

    private final String jdbcUrl;
    private final Path database;
    private final ObjectMapper mapper;
    private final int maxAutoAttempts;
    private final int maxTotalAttempts;
    private final long maxModelTokens;
    private final long maxToolCalls;
    private final AtomicLong duplicatePrevented = new AtomicLong();
    private final ThreadLocal<Connection> transaction = new ThreadLocal<>();

    public SqliteMissionStore(Path database, ObjectMapper mapper) {
        this(database, mapper, 2, 3, 200_000, 100);
    }

    public SqliteMissionStore(
            Path database,
            ObjectMapper mapper,
            int maxAutoAttempts,
            int maxTotalAttempts,
            long maxModelTokens,
            long maxToolCalls) {
        Path normalized = database.toAbsolutePath().normalize();
        if (maxAutoAttempts < 1 || maxTotalAttempts < maxAutoAttempts || maxTotalAttempts > 3) {
            throw new IllegalArgumentException("Mission Attempt limits are invalid");
        }
        if (maxModelTokens < 1 || maxModelTokens > 2_000_000 || maxToolCalls < 1 || maxToolCalls > 200) {
            throw new IllegalArgumentException("Mission usage limits are invalid");
        }
        try {
            Files.createDirectories(normalized.getParent());
        } catch (IOException exception) {
            throw new MissionException("MISSION_STORE_FAILED", "Mission data directory is unavailable", exception);
        }
        this.database = normalized;
        jdbcUrl = "jdbc:sqlite:" + normalized;
        this.mapper = mapper.copy();
        this.maxAutoAttempts = maxAutoAttempts;
        this.maxTotalAttempts = maxTotalAttempts;
        this.maxModelTokens = maxModelTokens;
        this.maxToolCalls = maxToolCalls;
        migrate();
    }

    Path database() {
        return database;
    }

    @Override
    public synchronized <T> T execute(Supplier<T> work) {
        if (transaction.get() != null) return work.get();
        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
            configure(connection);
            try (var begin = connection.createStatement()) {
                begin.execute("BEGIN IMMEDIATE");
            }
            transaction.set(connection);
            try {
                T value = work.get();
                executeControl(connection, "COMMIT");
                return value;
            } catch (RuntimeException | Error failure) {
                rollbackControl(connection, failure);
                throw failure;
            } finally {
                transaction.remove();
            }
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    @Override
    public MissionCommandReservation reserveCommand(MissionCommandBinding proposal) {
        Connection connection = current();
        try (var statement = connection.prepareStatement(
                """
                INSERT OR IGNORE INTO personal_mission_command(
                    owner_scope, operation, idempotency_key, request_digest, mission_id, created_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, proposal.ownerScope());
            statement.setString(2, proposal.operation());
            statement.setString(3, proposal.idempotencyKey());
            statement.setString(4, proposal.requestDigest());
            statement.setString(5, proposal.missionId());
            statement.setLong(6, proposal.createdAt().toEpochMilli());
            boolean created = statement.executeUpdate() == 1;
            if (!created) duplicatePrevented.incrementAndGet();
            MissionCommandBinding binding = findCommand(
                            proposal.ownerScope(), proposal.operation(), proposal.idempotencyKey())
                    .orElseThrow();
            if (!binding.requestDigest().equals(proposal.requestDigest())) {
                throw new MissionException(
                        "MISSION_IDEMPOTENCY_CONFLICT", "Idempotency-Key was reused with another payload");
            }
            return new MissionCommandReservation(binding, created);
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    @Override
    public void insert(PersonalMission mission) {
        PersonalMission.Persistence value = mission.persistence();
        try (var statement = current()
                .prepareStatement(
                        """
                INSERT INTO personal_mission(
                    mission_id, conversation_id, owner_scope, objective, acceptance_json, constraints_json,
                    selected_skill_id, selected_skill_binding, mode, research_brief_json, state, active_plan_revision_no, confirmed_plan_revision_no, failure_code,
                    version, created_at_ms, updated_at_ms, confirmed_at_ms, finished_at_ms, deadline_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            bindMission(statement, value);
            statement.executeUpdate();
            appendEvent(value.missionId(), "MISSION_CREATED", value.createdAt());
        } catch (SQLException exception) {
            throw constraint(exception);
        }
    }

    @Override
    public void save(PersonalMission mission, long expectedVersion) {
        PersonalMission.Persistence value = mission.persistence();
        try (var statement = current()
                .prepareStatement(
                        """
                UPDATE personal_mission SET
                    state=?, active_plan_revision_no=?, confirmed_plan_revision_no=?, failure_code=?,
                    version=?, updated_at_ms=?, confirmed_at_ms=?, finished_at_ms=?
                WHERE mission_id=? AND owner_scope=? AND version=?
                """)) {
            statement.setString(1, value.state().name());
            nullableInteger(statement, 2, value.activePlanRevisionNo());
            nullableInteger(statement, 3, value.confirmedPlanRevisionNo());
            nullableString(statement, 4, value.failureCode());
            statement.setLong(5, value.version());
            statement.setLong(6, value.updatedAt().toEpochMilli());
            nullableInstant(statement, 7, value.confirmedAt());
            nullableInstant(statement, 8, value.finishedAt());
            statement.setString(9, value.missionId());
            statement.setString(10, value.ownerScope());
            statement.setLong(11, expectedVersion);
            if (statement.executeUpdate() != 1) {
                throw new MissionException("MISSION_REVISION_STALE", "Mission revision is stale");
            }
            persistRevisions(value);
            if (value.confirmedPlanRevisionNo().isEmpty()) replaceActiveTasks(value);
            appendEvent(value.missionId(), "MISSION_" + value.state().name(), value.updatedAt());
        } catch (SQLException exception) {
            throw constraint(exception);
        }
    }

    @Override
    public Optional<PersonalMission> find(String missionId, String ownerScope) {
        try (var statement =
                current().prepareStatement("SELECT * FROM personal_mission WHERE mission_id=? AND owner_scope=?")) {
            statement.setString(1, missionId);
            statement.setString(2, ownerScope);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readMission(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    @Override
    public Optional<PersonalMission> findActive(String conversationId, String ownerScope) {
        try (var statement = current()
                .prepareStatement(
                        """
                SELECT * FROM personal_mission
                WHERE conversation_id=? AND owner_scope=?
                  AND state NOT IN ('COMPLETED','PARTIALLY_COMPLETED','FAILED','CANCELLED')
                """)) {
            statement.setString(1, conversationId);
            statement.setString(2, ownerScope);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readMission(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    @Override
    public List<PersonalMission> list(
            String ownerScope, Optional<String> conversationId, Optional<MissionListCursor> cursor, int limit) {
        String sql = "SELECT * FROM personal_mission WHERE owner_scope=?"
                + (conversationId.isPresent() ? " AND conversation_id=?" : "")
                + (cursor.isPresent() ? " AND (updated_at_ms < ? OR (updated_at_ms = ? AND mission_id < ?))" : "")
                + " ORDER BY updated_at_ms DESC, mission_id DESC LIMIT ?";
        try (var statement = current().prepareStatement(sql)) {
            statement.setString(1, ownerScope);
            int index = 2;
            if (conversationId.isPresent()) statement.setString(index++, conversationId.orElseThrow());
            if (cursor.isPresent()) {
                MissionListCursor value = cursor.orElseThrow();
                statement.setLong(index++, value.updatedAt().toEpochMilli());
                statement.setLong(index++, value.updatedAt().toEpochMilli());
                statement.setString(index++, value.missionId());
            }
            statement.setInt(index, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<PersonalMission> values = new ArrayList<>();
                while (result.next()) values.add(readMission(result));
                return List.copyOf(values);
            }
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    public int schemaVersion() {
        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
            configure(connection);
            try (var statement = connection.createStatement();
                    var result = statement.executeQuery("SELECT MAX(version) FROM personal_schema_history")) {
                return result.next() ? result.getInt(1) : 0;
            }
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    public OperationalSnapshot operationalSnapshot(Instant now) {
        return execute(() -> {
            try {
                Map<String, Long> states = new LinkedHashMap<>();
                try (var statement = current()
                                .prepareStatement(
                                        "SELECT state,COUNT(*) FROM personal_mission GROUP BY state ORDER BY state");
                        var result = statement.executeQuery()) {
                    while (result.next()) states.put(result.getString(1), result.getLong(2));
                }
                long activeMissions = scalar(
                        "SELECT COUNT(*) FROM personal_mission WHERE state NOT IN ('COMPLETED','PARTIALLY_COMPLETED','FAILED','CANCELLED')");
                long activeAttempts = scalar(
                        "SELECT COUNT(*) FROM personal_mission_task_attempt WHERE state IN ('CREATED','DISPATCH_PENDING','BOUND','SETTLEMENT_PENDING')");
                long unsettledAttempts = scalar(
                        "SELECT COUNT(*) FROM personal_mission_task_attempt WHERE state NOT IN ('SETTLED','FAILED','OUTCOME_UNKNOWN','CANCELLED')");
                long pendingOutbox =
                        scalar("SELECT COUNT(*) FROM personal_mission_outbox WHERE state IN ('PENDING','CLAIMED')");
                long blockedTasks = scalar("SELECT COUNT(*) FROM personal_mission_task WHERE state='BLOCKED'");
                long outcomeUnknown =
                        scalar("SELECT COUNT(*) FROM personal_mission_task_attempt WHERE state='OUTCOME_UNKNOWN'");
                long budgetExhausted = scalar(
                        "SELECT COUNT(*) FROM personal_mission_task WHERE block_code='MISSION_BUDGET_EXHAUSTED'");
                long modelTokens = scalar("SELECT COALESCE(SUM(usage_model_tokens),0) FROM personal_mission");
                long modelCalls = scalar("SELECT COALESCE(SUM(usage_model_calls),0) FROM personal_mission");
                long toolCalls = scalar("SELECT COALESCE(SUM(usage_tool_calls),0) FROM personal_mission");
                Optional<Long> oldestAge = Optional.empty();
                try (var statement = current()
                                .prepareStatement(
                                        "SELECT MIN(created_at_ms) FROM personal_mission_outbox WHERE state IN ('PENDING','CLAIMED')");
                        var result = statement.executeQuery()) {
                    if (result.next()) {
                        long value = result.getLong(1);
                        if (!result.wasNull()) oldestAge = Optional.of(Math.max(0, now.toEpochMilli() - value));
                    }
                }
                return new OperationalSnapshot(
                        Map.copyOf(states),
                        activeMissions,
                        activeAttempts,
                        unsettledAttempts,
                        pendingOutbox,
                        oldestAge,
                        blockedTasks,
                        outcomeUnknown,
                        budgetExhausted,
                        modelTokens,
                        modelCalls,
                        toolCalls,
                        duplicatePrevented.get());
            } catch (SQLException exception) {
                throw failure(exception);
            }
        });
    }

    public void requireQuiescent(Instant now) {
        OperationalSnapshot snapshot = operationalSnapshot(now);
        if (snapshot.activeMissions() != 0 || snapshot.unsettledAttempts() != 0 || snapshot.pendingOutbox() != 0) {
            throw new MissionException(
                    "MISSION_NOT_QUIESCENT",
                    "Active Mission, unsettled Attempt, or pending Outbox prevents maintenance");
        }
    }

    private long scalar(String sql) throws SQLException {
        try (var statement = current().prepareStatement(sql);
                var result = statement.executeQuery()) {
            return result.next() ? result.getLong(1) : 0;
        }
    }

    public record OperationalSnapshot(
            Map<String, Long> missionStates,
            long activeMissions,
            long activeAttempts,
            long unsettledAttempts,
            long pendingOutbox,
            Optional<Long> oldestOutboxAgeMillis,
            long blockedTasks,
            long outcomeUnknownAttempts,
            long budgetExhaustedTasks,
            long modelTokens,
            long modelCalls,
            long toolCalls,
            long duplicatePrevented) {}

    public void registerDispatcher(String processId, String instanceId, Instant now) {
        execute(() -> {
            try (var statement = current()
                    .prepareStatement(
                            "INSERT INTO personal_mission_dispatcher_owner(owner_name,process_id,instance_id,started_at_ms,heartbeat_at_ms,schema_version) VALUES ('pa-mission',?,?,?,?,?) ON CONFLICT(owner_name) DO UPDATE SET process_id=excluded.process_id,instance_id=excluded.instance_id,started_at_ms=excluded.started_at_ms,heartbeat_at_ms=excluded.heartbeat_at_ms,schema_version=excluded.schema_version")) {
                statement.setString(1, processId);
                statement.setString(2, instanceId);
                statement.setLong(3, now.toEpochMilli());
                statement.setLong(4, now.toEpochMilli());
                statement.setInt(5, SCHEMA_VERSION);
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw failure(exception);
            }
            return null;
        });
    }

    public void heartbeatDispatcher(String instanceId, Instant now) {
        execute(() -> {
            try (var statement = current()
                    .prepareStatement(
                            "UPDATE personal_mission_dispatcher_owner SET heartbeat_at_ms=? WHERE owner_name='pa-mission' AND instance_id=?")) {
                statement.setLong(1, now.toEpochMilli());
                statement.setString(2, instanceId);
                if (statement.executeUpdate() != 1) {
                    throw new MissionException(
                            "MISSION_DISPATCHER_OWNERSHIP_LOST", "Mission dispatcher ownership was lost");
                }
            } catch (SQLException exception) {
                throw failure(exception);
            }
            return null;
        });
    }

    @Override
    public Optional<MissionDispatchIntent> prepareAndClaimNext(String dispatcherId, Instant now, Instant staleBefore) {
        return execute(() -> prepareAndClaimNextInTransaction(dispatcherId, now, staleBefore));
    }

    @Override
    public void bind(MissionDispatchIntent intent, String sessionId, String runId, Instant now) {
        execute(() -> {
            try (var attempt = current()
                            .prepareStatement(
                                    """
                    UPDATE personal_mission_task_attempt
                    SET state='BOUND', session_id=?, run_id=?, started_at_ms=?, updated_at_ms=?, version=version+1
                    WHERE mission_id=? AND task_id=? AND attempt_no=?
                      AND state IN ('DISPATCH_PENDING','BOUND')
                      AND (session_id IS NULL OR session_id=?) AND (run_id IS NULL OR run_id=?)
                    """);
                    var outbox = current()
                            .prepareStatement(
                                    """
                    UPDATE personal_mission_outbox SET state='COMPLETED', completed_at_ms=?
                    WHERE outbox_id=? AND state IN ('CLAIMED','COMPLETED')
                    """)) {
                attempt.setString(1, sessionId);
                attempt.setString(2, runId);
                attempt.setLong(3, now.toEpochMilli());
                attempt.setLong(4, now.toEpochMilli());
                attempt.setString(5, intent.missionId());
                attempt.setString(6, intent.taskId());
                attempt.setInt(7, intent.attemptNo());
                attempt.setString(8, sessionId);
                attempt.setString(9, runId);
                if (attempt.executeUpdate() != 1) {
                    throw new MissionException(
                            "MISSION_BINDING_CONFLICT", "Task Run binding conflicts with persisted state");
                }
                outbox.setLong(1, now.toEpochMilli());
                outbox.setString(2, intent.outboxId());
                outbox.executeUpdate();
                touchMission(intent.missionId(), now);
                appendEvent(intent.missionId(), "MISSION_TASK_BOUND", now);
            } catch (SQLException exception) {
                throw failure(exception);
            }
            return null;
        });
    }

    @Override
    public void failDispatch(MissionDispatchIntent intent, String failureCode, boolean retryable, Instant now) {
        execute(() -> {
            MissionTaskAttempt attempt = requireAttempt(intent.missionId(), intent.taskId(), intent.attemptNo());
            settleFailedInTransaction(attempt, failureCode, retryable, now);
            completeOutbox(intent.outboxId(), now);
            return null;
        });
    }

    @Override
    public List<MissionTaskAttempt> activeAttempts() {
        return execute(() -> {
            try (var statement = current()
                            .prepareStatement(
                                    """
                    SELECT * FROM personal_mission_task_attempt
                    WHERE state IN ('CREATED','DISPATCH_PENDING','BOUND','SETTLEMENT_PENDING')
                    ORDER BY created_at_ms, mission_id, task_id
                    """);
                    var result = statement.executeQuery()) {
                List<MissionTaskAttempt> values = new ArrayList<>();
                while (result.next()) values.add(readAttempt(result));
                return List.copyOf(values);
            } catch (SQLException exception) {
                throw failure(exception);
            }
        });
    }

    @Override
    public MissionState missionState(String missionId) {
        return execute(() -> {
            try (var statement = current().prepareStatement("SELECT state FROM personal_mission WHERE mission_id=?")) {
                statement.setString(1, missionId);
                try (var result = statement.executeQuery()) {
                    if (!result.next()) throw new MissionException("MISSION_NOT_FOUND", "Mission is unavailable");
                    return enumValue(MissionState.class, result.getString(1), "mission state");
                }
            } catch (SQLException exception) {
                throw failure(exception);
            }
        });
    }

    @Override
    public boolean deadlineExceeded(String missionId, Instant now) {
        return execute(() -> {
            try (var statement = current()
                    .prepareStatement(
                            "SELECT 1 FROM personal_mission WHERE mission_id=? AND state IN ('RUNNING','WAITING_USER') AND deadline_at_ms<=?")) {
                statement.setString(1, missionId);
                statement.setLong(2, now.toEpochMilli());
                try (var result = statement.executeQuery()) {
                    return result.next();
                }
            } catch (SQLException exception) {
                throw failure(exception);
            }
        });
    }

    @Override
    public void expireForPartialSynthesis(String missionId, Instant now) {
        execute(() -> {
            try {
                expireMissionForPartialSynthesis(missionId, now);
            } catch (SQLException exception) {
                throw failure(exception);
            }
            return null;
        });
    }

    @Override
    public void waitingForUser(MissionTaskAttempt attempt, Instant now) {
        execute(() -> {
            try {
                updateMissionState(attempt.missionId(), "WAITING_USER", now, "state IN ('RUNNING','WAITING_USER')");
            } catch (SQLException exception) {
                throw failure(exception);
            }
            return null;
        });
    }

    @Override
    public void settleCompleted(MissionTaskAttempt attempt, String resultDigest, String resultJson, Instant now) {
        settleCompleted(attempt, resultDigest, resultJson, MissionUsage.NONE, now);
    }

    @Override
    public void settleCompleted(
            MissionTaskAttempt attempt, String resultDigest, String resultJson, MissionUsage usage, Instant now) {
        execute(() -> {
            if (!settleAttempt(attempt, MissionTaskAttemptState.SETTLED, resultDigest, null, now)) return null;
            addUsage(attempt.missionId(), usage, now);
            try (var statement = current()
                    .prepareStatement(
                            "UPDATE personal_mission_task SET state='COMPLETED',result_json=?,result_digest=?,block_code=NULL,updated_at_ms=?,version=version+1 WHERE mission_id=? AND task_id=?")) {
                statement.setString(1, resultJson);
                statement.setString(2, resultDigest);
                statement.setLong(3, now.toEpochMilli());
                statement.setString(4, attempt.missionId());
                statement.setString(5, attempt.taskId());
                statement.executeUpdate();
                updateMissionState(attempt.missionId(), "RUNNING", now, "state IN ('RUNNING','WAITING_USER')");
                appendEvent(attempt.missionId(), "MISSION_TASK_COMPLETED", now);
            } catch (SQLException exception) {
                throw failure(exception);
            }
            return null;
        });
    }

    @Override
    public void settleFailed(MissionTaskAttempt attempt, String failureCode, boolean retryable, Instant now) {
        settleFailed(attempt, failureCode, retryable, MissionUsage.NONE, now);
    }

    @Override
    public void settleFailed(
            MissionTaskAttempt attempt, String failureCode, boolean retryable, MissionUsage usage, Instant now) {
        execute(() -> {
            if (settleFailedInTransaction(attempt, failureCode, retryable, now)) {
                addUsage(attempt.missionId(), usage, now);
            }
            return null;
        });
    }

    @Override
    public void settleCancelled(MissionTaskAttempt attempt, Instant now) {
        settleCancelled(attempt, MissionUsage.NONE, now);
    }

    @Override
    public void settleCancelled(MissionTaskAttempt attempt, MissionUsage usage, Instant now) {
        execute(() -> {
            if (!settleAttempt(attempt, MissionTaskAttemptState.CANCELLED, null, null, now)) return null;
            addUsage(attempt.missionId(), usage, now);
            try (var statement = current()
                    .prepareStatement(
                            "UPDATE personal_mission_task SET state='CANCELLED',updated_at_ms=?,version=version+1 WHERE mission_id=? AND task_id=?")) {
                statement.setLong(1, now.toEpochMilli());
                statement.setString(2, attempt.missionId());
                statement.setString(3, attempt.taskId());
                statement.executeUpdate();
                appendEvent(attempt.missionId(), "MISSION_TASK_CANCELLED", now);
            } catch (SQLException exception) {
                throw failure(exception);
            }
            return null;
        });
    }

    @Override
    public void cancelMission(String missionId, Instant now) {
        execute(() -> {
            try (var statement = current()
                    .prepareStatement(
                            "UPDATE personal_mission_task SET state='CANCELLED',updated_at_ms=?,version=version+1 WHERE mission_id=? AND state NOT IN ('COMPLETED','CANCELLED')")) {
                statement.setLong(1, now.toEpochMilli());
                statement.setString(2, missionId);
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw failure(exception);
            }
            return null;
        });
    }

    @Override
    public MissionExecutionSnapshot snapshot(String missionId) {
        return execute(() -> executionSnapshot(missionId));
    }

    @Override
    public void retryBlocked(String missionId, String ownerScope, String taskId, Instant now) {
        execute(() -> {
            try (var statement = current()
                    .prepareStatement(
                            """
                    UPDATE personal_mission_task SET state='READY',block_code=NULL,updated_at_ms=?,version=version+1
                    WHERE mission_id=? AND task_id=? AND state='BLOCKED'
                      AND latest_attempt_no < ?
                      AND block_code<>'MISSION_BUDGET_EXHAUSTED'
                      AND EXISTS (SELECT 1 FROM personal_mission m WHERE m.mission_id=? AND m.owner_scope=? AND m.state='WAITING_USER')
                    """)) {
                statement.setLong(1, now.toEpochMilli());
                statement.setString(2, missionId);
                statement.setString(3, taskId);
                statement.setInt(4, maxTotalAttempts);
                statement.setString(5, missionId);
                statement.setString(6, ownerScope);
                if (statement.executeUpdate() != 1) {
                    throw new MissionException(
                            "MISSION_TASK_NOT_RETRYABLE", "Mission Task is not blocked and retryable");
                }
                updateMissionState(missionId, "RUNNING", now, "state='WAITING_USER'");
                appendEvent(missionId, "MISSION_TASK_RETRY_REQUESTED", now);
            } catch (SQLException exception) {
                throw failure(exception);
            }
            return null;
        });
    }

    @Override
    public Optional<MissionSynthesisIntent> claimSynthesis(Instant now) {
        return execute(() -> {
            try {
                cancelDependentsOfExhaustedTasks(now);
                try (var select = current()
                        .prepareStatement(
                                """
                    SELECT mission_id,conversation_id,owner_scope,mode,objective
                    FROM personal_mission m
                    WHERE state IN ('RUNNING','WAITING_USER','SYNTHESIZING')
                      AND NOT EXISTS (SELECT 1 FROM personal_mission_task t
                        WHERE t.mission_id=m.mission_id
                          AND t.state NOT IN ('COMPLETED','BLOCKED','CANCELLED'))
                      AND NOT EXISTS (SELECT 1 FROM personal_mission_task t
                        WHERE t.mission_id=m.mission_id AND t.state='BLOCKED'
                          AND t.latest_attempt_no < ? AND t.block_code<>'MISSION_BUDGET_EXHAUSTED')
                      AND EXISTS (SELECT 1 FROM personal_mission_task t WHERE t.mission_id=m.mission_id)
                    ORDER BY created_at_ms,mission_id LIMIT 1
                    """)) {
                    select.setInt(1, maxTotalAttempts);
                    try (var result = select.executeQuery()) {
                        if (!result.next()) return Optional.empty();
                        String missionId = result.getString("mission_id");
                        updateMissionState(missionId, "SYNTHESIZING", now, "state IN ('RUNNING','WAITING_USER')");
                        List<String> taskResults = new ArrayList<>();
                        List<String> failedItems = new ArrayList<>();
                        try (var tasks = current()
                                .prepareStatement(
                                        "SELECT task_id,state,result_json,block_code FROM personal_mission_task WHERE mission_id=? ORDER BY ordinal")) {
                            tasks.setString(1, missionId);
                            try (var rows = tasks.executeQuery()) {
                                while (rows.next()) {
                                    String taskResult = rows.getString("result_json");
                                    if (taskResult != null) {
                                        taskResults.add(taskResult);
                                    } else {
                                        String code = rows.getString("block_code");
                                        failedItems.add(rows.getString("task_id") + ":" + rows.getString("state")
                                                + (code == null ? "" : ":" + code));
                                    }
                                }
                            }
                        }
                        return Optional.of(new MissionSynthesisIntent(
                                missionId,
                                result.getString("conversation_id"),
                                result.getString("owner_scope"),
                                enumValue(
                                        io.haifa.agent.personalassistant.application.mission.MissionMode.class,
                                        result.getString("mode"),
                                        "Mission mode"),
                                result.getString("objective"),
                                taskResults,
                                failedItems));
                    }
                }
            } catch (SQLException exception) {
                throw failure(exception);
            }
        });
    }

    @Override
    public void settleSynthesis(
            MissionSynthesisIntent intent,
            MissionRuntimeAccess.SynthesisRunResult synthesis,
            MissionPublishedResult published,
            Instant now) {
        execute(() -> {
            String terminalState = "PARTIAL".equals(published.completionKind()) ? "PARTIALLY_COMPLETED" : "COMPLETED";
            try (var statement = current()
                    .prepareStatement(
                            """
                    UPDATE personal_mission SET state=?,synthesis_session_id=?,synthesis_run_id=?,
                      final_artifact_id=?,final_message_key=?,artifact_refs_json=?,sources_json=?,final_result_json=?,
                      failure_code=NULL,updated_at_ms=?,finished_at_ms=?,version=version+1
                      WHERE mission_id=? AND state='SYNTHESIZING'
                    """)) {
                statement.setString(1, terminalState);
                statement.setString(2, synthesis.sessionId());
                statement.setString(3, synthesis.runId());
                statement.setString(4, published.finalArtifactId());
                statement.setString(5, "mission:" + intent.missionId() + ":final-message:v1");
                statement.setString(6, json(published.artifactIds()));
                statement.setString(7, json(published.sources()));
                statement.setString(8, published.structuredResult());
                statement.setLong(9, now.toEpochMilli());
                statement.setLong(10, now.toEpochMilli());
                statement.setString(11, intent.missionId());
                if (statement.executeUpdate() != 1) {
                    duplicatePrevented.incrementAndGet();
                    return null;
                }
                addUsage(intent.missionId(), synthesis.usage(), now);
                appendEvent(
                        intent.missionId(),
                        "PARTIALLY_COMPLETED".equals(terminalState)
                                ? "MISSION_SYNTHESIS_PARTIALLY_COMPLETED"
                                : "MISSION_SYNTHESIS_COMPLETED",
                        now);
            } catch (SQLException exception) {
                throw failure(exception);
            }
            return null;
        });
    }

    @Override
    public void failSynthesis(MissionSynthesisIntent intent, String failureCode, Instant now) {
        execute(() -> {
            try (var statement = current()
                    .prepareStatement(
                            """
                    UPDATE personal_mission SET state='FAILED',failure_code=?,updated_at_ms=?,finished_at_ms=?,
                      version=version+1 WHERE mission_id=? AND state='SYNTHESIZING'
                    """)) {
                statement.setString(1, failureCode);
                statement.setLong(2, now.toEpochMilli());
                statement.setLong(3, now.toEpochMilli());
                statement.setString(4, intent.missionId());
                statement.executeUpdate();
                appendEvent(intent.missionId(), "MISSION_SYNTHESIS_FAILED", now);
            } catch (SQLException exception) {
                throw failure(exception);
            }
            return null;
        });
    }

    private void migrate() {
        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
            configure(connection);
            try (var statement = connection.createStatement()) {
                statement.execute(
                        "CREATE TABLE IF NOT EXISTS personal_schema_history(version INTEGER PRIMARY KEY, checksum TEXT NOT NULL, installed_at_ms INTEGER NOT NULL)");
            }
            try (var statement = connection.createStatement();
                    var result =
                            statement.executeQuery("SELECT COALESCE(MAX(version),0) FROM personal_schema_history")) {
                if (result.next() && result.getInt(1) > SCHEMA_VERSION) {
                    throw new MissionException(
                            "MISSION_SCHEMA_NEWER_THAN_APPLICATION",
                            "Personal Mission schema is newer than this application");
                }
            }
            applyMigration(connection, 1, MIGRATION);
            applyMigration(connection, 2, MIGRATION_V2);
            applyMigration(connection, 3, MIGRATION_V3);
            applyMigration(connection, 4, MIGRATION_V4);
            applyMigration(connection, 5, MIGRATION_V5);
            applyMigration(connection, 6, MIGRATION_V6);
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    private void applyMigration(Connection connection, int version, String script) throws SQLException {
        String checksum = sha256(script);
        try (var query = connection.prepareStatement("SELECT checksum FROM personal_schema_history WHERE version=?")) {
            query.setInt(1, version);
            try (var result = query.executeQuery()) {
                if (result.next()) {
                    if (!checksum.equals(result.getString(1))) {
                        throw new MissionException(
                                "MISSION_SCHEMA_DRIFT", "Personal Mission migration checksum changed");
                    }
                    return;
                }
            }
        }
        connection.setAutoCommit(false);
        try {
            for (String sql : SqlScriptParser.parse(script)) {
                try (var statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            }
            try (var insert = connection.prepareStatement(
                    "INSERT INTO personal_schema_history(version, checksum, installed_at_ms) VALUES (?, ?, ?)")) {
                insert.setInt(1, version);
                insert.setString(2, checksum);
                insert.setLong(3, System.currentTimeMillis());
                insert.executeUpdate();
            }
            connection.commit();
        } catch (SQLException | RuntimeException failure) {
            rollback(connection, failure);
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private Optional<MissionDispatchIntent> prepareAndClaimNextInTransaction(
            String dispatcherId, Instant now, Instant staleBefore) {
        try {
            expireDeadlines(now);
            exhaustBudgets(now);
            if (!hasActiveAttempt()) {
                refreshReadyTasks(now);
                createNextAttempt(now);
            }
            try (var select = current()
                    .prepareStatement(
                            """
                    SELECT o.outbox_id,o.mission_id,o.task_id,o.attempt_no,o.payload_digest,
                           a.dispatch_key,m.owner_scope,t.objective,t.acceptance_json,
                           t.task_type,t.skill_ids_json,t.result_schema_id,t.result_schema_version
                    FROM personal_mission_outbox o
                    JOIN personal_mission_task_attempt a
                      ON a.mission_id=o.mission_id AND a.task_id=o.task_id AND a.attempt_no=o.attempt_no
                    JOIN personal_mission_task t ON t.mission_id=o.mission_id AND t.task_id=o.task_id
                    JOIN personal_mission m ON m.mission_id=o.mission_id
                    WHERE (o.state='PENDING' AND o.available_at_ms<=?)
                       OR (o.state='CLAIMED' AND o.claimed_at_ms<?)
                    ORDER BY o.available_at_ms,o.mission_id,o.task_id,o.outbox_id LIMIT 1
                    """)) {
                select.setLong(1, now.toEpochMilli());
                select.setLong(2, staleBefore.toEpochMilli());
                try (var result = select.executeQuery()) {
                    if (!result.next()) return Optional.empty();
                    String outboxId = result.getString("outbox_id");
                    try (var claim = current()
                            .prepareStatement(
                                    "UPDATE personal_mission_outbox SET state='CLAIMED',claimed_at_ms=?,claim_owner=? WHERE outbox_id=? AND (state='PENDING' OR (state='CLAIMED' AND claimed_at_ms<?))")) {
                        claim.setLong(1, now.toEpochMilli());
                        claim.setString(2, dispatcherId);
                        claim.setString(3, outboxId);
                        claim.setLong(4, staleBefore.toEpochMilli());
                        if (claim.executeUpdate() != 1) return Optional.empty();
                    }
                    return Optional.of(new MissionDispatchIntent(
                            outboxId,
                            result.getString("mission_id"),
                            result.getString("owner_scope"),
                            result.getString("task_id"),
                            result.getInt("attempt_no"),
                            result.getString("dispatch_key"),
                            result.getString("payload_digest"),
                            result.getString("objective"),
                            jsonList(result.getString("acceptance_json")),
                            result.getString("task_type"),
                            jsonList(result.getString("skill_ids_json")),
                            result.getString("result_schema_id"),
                            result.getString("result_schema_version"),
                            now));
                }
            }
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    private void cancelDependentsOfExhaustedTasks(Instant now) throws SQLException {
        try (var statement = current()
                .prepareStatement(
                        """
                WITH RECURSIVE descendants(mission_id, task_id) AS (
                  SELECT mission_id, task_id FROM personal_mission_task
                  WHERE state='BLOCKED' AND latest_attempt_no >= ?
                  UNION
                  SELECT dependency.mission_id, dependency.task_id
                  FROM personal_mission_task_dependency dependency
                  JOIN descendants parent
                    ON parent.mission_id=dependency.mission_id
                   AND parent.task_id=dependency.depends_on_task_id
                )
                UPDATE personal_mission_task AS task
                SET state='CANCELLED',block_code='MISSION_DEPENDENCY_BLOCKED',updated_at_ms=?,version=version+1
                WHERE state IN ('PLANNED','WAITING_DEPENDENCY','READY')
                  AND EXISTS (
                    SELECT 1 FROM descendants value
                    WHERE value.mission_id=task.mission_id AND value.task_id=task.task_id)
                """)) {
            statement.setInt(1, maxTotalAttempts);
            statement.setLong(2, now.toEpochMilli());
            statement.executeUpdate();
        }
    }

    private void exhaustBudgets(Instant now) throws SQLException {
        List<String> exhausted = new ArrayList<>();
        try (var statement = current()
                .prepareStatement(
                        """
                SELECT mission_id FROM personal_mission m
                WHERE state IN ('RUNNING','WAITING_USER')
                  AND (usage_model_tokens>=? OR usage_tool_calls>=?)
                  AND EXISTS (SELECT 1 FROM personal_mission_task t WHERE t.mission_id=m.mission_id
                    AND t.state NOT IN ('COMPLETED','BLOCKED','CANCELLED'))
                ORDER BY created_at_ms,mission_id
                """)) {
            statement.setLong(1, maxModelTokens);
            statement.setLong(2, maxToolCalls);
            try (var result = statement.executeQuery()) {
                while (result.next()) exhausted.add(result.getString(1));
            }
        }
        for (String missionId : exhausted) {
            try (var tasks = current()
                            .prepareStatement(
                                    """
                    UPDATE personal_mission_task
                    SET state='BLOCKED',block_code='MISSION_BUDGET_EXHAUSTED',updated_at_ms=?,version=version+1
                    WHERE mission_id=? AND state NOT IN ('COMPLETED','BLOCKED','CANCELLED')
                    """);
                    var mission = current()
                            .prepareStatement(
                                    """
                    UPDATE personal_mission SET state='WAITING_USER',failure_code='MISSION_BUDGET_EXHAUSTED',
                      updated_at_ms=?,version=version+1 WHERE mission_id=? AND state IN ('RUNNING','WAITING_USER')
                    """)) {
                tasks.setLong(1, now.toEpochMilli());
                tasks.setString(2, missionId);
                tasks.executeUpdate();
                mission.setLong(1, now.toEpochMilli());
                mission.setString(2, missionId);
                mission.executeUpdate();
                appendEvent(missionId, "MISSION_BUDGET_EXHAUSTED", now);
            }
        }
    }

    private void expireDeadlines(Instant now) throws SQLException {
        List<String> expired = new ArrayList<>();
        try (var statement = current()
                .prepareStatement(
                        """
                SELECT mission_id FROM personal_mission m
                WHERE state IN ('RUNNING','WAITING_USER') AND deadline_at_ms<=?
                  AND NOT EXISTS (SELECT 1 FROM personal_mission_task_attempt a
                    WHERE a.mission_id=m.mission_id
                      AND a.state IN ('CREATED','DISPATCH_PENDING','BOUND','SETTLEMENT_PENDING'))
                ORDER BY created_at_ms,mission_id
                """)) {
            statement.setLong(1, now.toEpochMilli());
            try (var result = statement.executeQuery()) {
                while (result.next()) expired.add(result.getString(1));
            }
        }
        for (String missionId : expired) expireMissionForPartialSynthesis(missionId, now);
    }

    private void expireMissionForPartialSynthesis(String missionId, Instant now) throws SQLException {
        try (var tasks = current()
                        .prepareStatement(
                                """
                UPDATE personal_mission_task SET state='CANCELLED',block_code='MISSION_DEADLINE_EXCEEDED',
                  updated_at_ms=?,version=version+1
                WHERE mission_id=? AND state NOT IN ('COMPLETED','BLOCKED')
                """);
                var mission = current()
                        .prepareStatement(
                                """
                UPDATE personal_mission SET state='WAITING_USER',failure_code='MISSION_DEADLINE_EXCEEDED',
                  updated_at_ms=?,version=version+1
                WHERE mission_id=? AND state IN ('RUNNING','WAITING_USER')
                """)) {
            tasks.setLong(1, now.toEpochMilli());
            tasks.setString(2, missionId);
            tasks.executeUpdate();
            mission.setLong(1, now.toEpochMilli());
            mission.setString(2, missionId);
            if (mission.executeUpdate() == 1) appendEvent(missionId, "MISSION_DEADLINE_EXCEEDED", now);
        }
    }

    private boolean hasActiveAttempt() throws SQLException {
        try (var statement = current()
                        .prepareStatement(
                                "SELECT 1 FROM personal_mission_task_attempt WHERE state IN ('CREATED','DISPATCH_PENDING','BOUND','SETTLEMENT_PENDING') LIMIT 1");
                var result = statement.executeQuery()) {
            return result.next();
        }
    }

    private void refreshReadyTasks(Instant now) throws SQLException {
        try (var ready = current()
                        .prepareStatement(
                                """
                UPDATE personal_mission_task AS t SET state='READY',updated_at_ms=?,version=version+1
                WHERE state IN ('PLANNED','WAITING_DEPENDENCY')
                  AND EXISTS (SELECT 1 FROM personal_mission m WHERE m.mission_id=t.mission_id AND m.state='RUNNING')
                  AND NOT EXISTS (
                    SELECT 1 FROM personal_mission_task_dependency d
                    JOIN personal_mission_task dependency
                      ON dependency.mission_id=d.mission_id AND dependency.task_id=d.depends_on_task_id
                    WHERE d.mission_id=t.mission_id AND d.task_id=t.task_id AND dependency.state<>'COMPLETED')
                """);
                var waiting = current()
                        .prepareStatement(
                                """
                UPDATE personal_mission_task AS t SET state='WAITING_DEPENDENCY',updated_at_ms=?,version=version+1
                WHERE state='PLANNED'
                  AND EXISTS (SELECT 1 FROM personal_mission m WHERE m.mission_id=t.mission_id AND m.state='RUNNING')
                  AND EXISTS (
                    SELECT 1 FROM personal_mission_task_dependency d
                    JOIN personal_mission_task dependency
                      ON dependency.mission_id=d.mission_id AND dependency.task_id=d.depends_on_task_id
                    WHERE d.mission_id=t.mission_id AND d.task_id=t.task_id AND dependency.state<>'COMPLETED')
                """)) {
            ready.setLong(1, now.toEpochMilli());
            ready.executeUpdate();
            waiting.setLong(1, now.toEpochMilli());
            waiting.executeUpdate();
        }
    }

    private void createNextAttempt(Instant now) throws SQLException {
        try (var select = current()
                        .prepareStatement(
                                """
                SELECT t.mission_id,t.task_id,t.latest_attempt_no,t.objective,t.acceptance_json,
                       t.result_schema_id,t.result_schema_version
                FROM personal_mission_task t JOIN personal_mission m ON m.mission_id=t.mission_id
                WHERE t.state='READY' AND m.state='RUNNING'
                ORDER BY COALESCE(m.started_at_ms,m.confirmed_at_ms),t.mission_id,t.ordinal,t.task_id LIMIT 1
                """);
                var result = select.executeQuery()) {
            if (!result.next()) return;
            String missionId = result.getString("mission_id");
            String taskId = result.getString("task_id");
            int attemptNo = result.getInt("latest_attempt_no") + 1;
            String dispatchKey = "mission:" + missionId + ":task:" + taskId + ":attempt:" + attemptNo;
            String payloadDigest = sha256(result.getString("objective") + "\u0000"
                    + result.getString("acceptance_json") + "\u0000"
                    + result.getString("result_schema_id") + "\u0000"
                    + result.getString("result_schema_version"));
            try (var attempt = current()
                            .prepareStatement(
                                    """
                    INSERT INTO personal_mission_task_attempt(
                      mission_id,task_id,attempt_no,attempt_kind,dispatch_key,dispatch_payload_digest,state,
                      version,created_at_ms,updated_at_ms) VALUES (?,?,?,'AUTO',?,?,'DISPATCH_PENDING',0,?,?)
                    """);
                    var task = current()
                            .prepareStatement(
                                    "UPDATE personal_mission_task SET latest_attempt_no=?,updated_at_ms=?,version=version+1 WHERE mission_id=? AND task_id=? AND state='READY'");
                    var outbox = current()
                            .prepareStatement(
                                    """
                    INSERT INTO personal_mission_outbox(
                      outbox_id,mission_id,task_id,attempt_no,intent_type,payload_json,payload_digest,state,
                      available_at_ms,created_at_ms) VALUES (?,?,?,?,'START_TASK','{}',?,'PENDING',?,?)
                    """)) {
                attempt.setString(1, missionId);
                attempt.setString(2, taskId);
                attempt.setInt(3, attemptNo);
                attempt.setString(4, dispatchKey);
                attempt.setString(5, payloadDigest);
                attempt.setLong(6, now.toEpochMilli());
                attempt.setLong(7, now.toEpochMilli());
                attempt.executeUpdate();
                task.setInt(1, attemptNo);
                task.setLong(2, now.toEpochMilli());
                task.setString(3, missionId);
                task.setString(4, taskId);
                task.executeUpdate();
                outbox.setString(1, dispatchKey);
                outbox.setString(2, missionId);
                outbox.setString(3, taskId);
                outbox.setInt(4, attemptNo);
                outbox.setString(5, payloadDigest);
                outbox.setLong(6, now.toEpochMilli());
                outbox.setLong(7, now.toEpochMilli());
                outbox.executeUpdate();
                touchMission(missionId, now);
                appendEvent(missionId, "MISSION_TASK_DISPATCH_PENDING", now);
            }
        }
    }

    private boolean settleFailedInTransaction(
            MissionTaskAttempt attempt, String failureCode, boolean retryable, Instant now) {
        MissionTaskAttemptState terminal = failureCode.endsWith("OUTCOME_UNKNOWN")
                ? MissionTaskAttemptState.OUTCOME_UNKNOWN
                : MissionTaskAttemptState.FAILED;
        if (!settleAttempt(attempt, terminal, null, failureCode, now)) return false;
        boolean autoRetry = retryable && attempt.attemptNo() < maxAutoAttempts;
        try (var task = current()
                .prepareStatement(
                        "UPDATE personal_mission_task SET state=?,block_code=?,updated_at_ms=?,version=version+1 WHERE mission_id=? AND task_id=?")) {
            task.setString(1, autoRetry ? "READY" : "BLOCKED");
            task.setString(2, autoRetry ? null : failureCode);
            task.setLong(3, now.toEpochMilli());
            task.setString(4, attempt.missionId());
            task.setString(5, attempt.taskId());
            task.executeUpdate();
            updateMissionState(
                    attempt.missionId(),
                    autoRetry ? "RUNNING" : "WAITING_USER",
                    now,
                    "state IN ('RUNNING','WAITING_USER')");
            appendEvent(attempt.missionId(), autoRetry ? "MISSION_TASK_RETRY_SCHEDULED" : "MISSION_TASK_BLOCKED", now);
        } catch (SQLException exception) {
            throw failure(exception);
        }
        return true;
    }

    private void addUsage(String missionId, MissionUsage usage, Instant now) {
        try (var statement = current()
                .prepareStatement(
                        """
                UPDATE personal_mission
                SET usage_model_tokens=usage_model_tokens+?,usage_model_calls=usage_model_calls+?,
                    usage_tool_calls=usage_tool_calls+?,updated_at_ms=?
                WHERE mission_id=?
                """)) {
            statement.setLong(1, usage.modelTokens());
            statement.setLong(2, usage.modelCalls());
            statement.setLong(3, usage.toolCalls());
            statement.setLong(4, now.toEpochMilli());
            statement.setString(5, missionId);
            if (statement.executeUpdate() != 1) {
                throw new MissionException("MISSION_NOT_FOUND", "Mission is unavailable");
            }
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    private boolean settleAttempt(
            MissionTaskAttempt attempt,
            MissionTaskAttemptState target,
            String resultDigest,
            String failureCode,
            Instant now) {
        try (var statement = current()
                .prepareStatement(
                        "UPDATE personal_mission_task_attempt SET state=?,result_digest=?,failure_code=?,settled_at_ms=?,updated_at_ms=?,version=version+1 WHERE mission_id=? AND task_id=? AND attempt_no=? AND state IN ('CREATED','DISPATCH_PENDING','BOUND','SETTLEMENT_PENDING')")) {
            statement.setString(1, target.name());
            statement.setString(2, resultDigest);
            statement.setString(3, failureCode);
            statement.setLong(4, now.toEpochMilli());
            statement.setLong(5, now.toEpochMilli());
            statement.setString(6, attempt.missionId());
            statement.setString(7, attempt.taskId());
            statement.setInt(8, attempt.attemptNo());
            boolean updated = statement.executeUpdate() == 1;
            if (!updated) duplicatePrevented.incrementAndGet();
            return updated;
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    private MissionExecutionSnapshot executionSnapshot(String missionId) {
        try (var tasks = current()
                        .prepareStatement(
                                "SELECT task_id,ordinal,state,latest_attempt_no,result_digest,block_code FROM personal_mission_task WHERE mission_id=? ORDER BY ordinal");
                var latest = current()
                        .prepareStatement(
                                "SELECT * FROM personal_mission_task_attempt WHERE mission_id=? ORDER BY created_at_ms DESC,task_id DESC,attempt_no DESC LIMIT 1");
                var delivery = current()
                        .prepareStatement(
                                "SELECT artifact_refs_json,sources_json,final_result_json FROM personal_mission WHERE mission_id=?")) {
            tasks.setString(1, missionId);
            List<MissionExecutionSnapshot.TaskExecution> values = new ArrayList<>();
            int completed = 0;
            int blocked = 0;
            String currentTask = null;
            try (var result = tasks.executeQuery()) {
                while (result.next()) {
                    MissionTaskState state = enumValue(MissionTaskState.class, result.getString("state"), "task state");
                    if (state == MissionTaskState.COMPLETED) completed++;
                    if (state == MissionTaskState.BLOCKED) blocked++;
                    if ((state == MissionTaskState.READY || state == MissionTaskState.WAITING_DEPENDENCY)
                            && currentTask == null) {
                        currentTask = result.getString("task_id");
                    }
                    values.add(new MissionExecutionSnapshot.TaskExecution(
                            result.getString("task_id"),
                            result.getInt("ordinal"),
                            state,
                            result.getInt("latest_attempt_no"),
                            findLatestRunId(missionId, result.getString("task_id")),
                            Optional.ofNullable(result.getString("result_digest")),
                            Optional.ofNullable(result.getString("block_code"))));
                }
            }
            latest.setString(1, missionId);
            Optional<MissionTaskAttempt> attempt;
            try (var result = latest.executeQuery()) {
                attempt = result.next() ? Optional.of(readAttempt(result)) : Optional.empty();
            }
            if (attempt.filter(value -> value.state().active()).isPresent())
                currentTask = attempt.orElseThrow().taskId();
            boolean settled = !values.isEmpty()
                    && values.stream()
                            .allMatch(value -> value.state() == MissionTaskState.COMPLETED
                                    || value.state() == MissionTaskState.BLOCKED
                                    || value.state() == MissionTaskState.CANCELLED);
            DispatcherHealth health = dispatcherHealth();
            delivery.setString(1, missionId);
            List<String> artifacts = List.of();
            List<String> sources = List.of();
            Optional<String> finalResult = Optional.empty();
            try (var result = delivery.executeQuery()) {
                if (result.next()) {
                    artifacts = jsonList(result.getString("artifact_refs_json"));
                    sources = jsonList(result.getString("sources_json"));
                    finalResult = Optional.ofNullable(result.getString("final_result_json"));
                }
            }
            return new MissionExecutionSnapshot(
                    health.status(),
                    health.recovering(),
                    settled,
                    completed,
                    blocked,
                    Optional.ofNullable(currentTask),
                    values,
                    attempt,
                    artifacts,
                    sources,
                    finalResult);
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    private DispatcherHealth dispatcherHealth() throws SQLException {
        try (var statement = current()
                        .prepareStatement(
                                "SELECT heartbeat_at_ms FROM personal_mission_dispatcher_owner WHERE owner_name='pa-mission'");
                var result = statement.executeQuery()) {
            if (!result.next()) return new DispatcherHealth("NOT_READY", true);
            long age = Math.max(0, System.currentTimeMillis() - result.getLong(1));
            return age <= 10_000 ? new DispatcherHealth("READY", false) : new DispatcherHealth("NOT_READY", true);
        }
    }

    private Optional<String> findLatestRunId(String missionId, String taskId) throws SQLException {
        try (var statement = current()
                .prepareStatement(
                        "SELECT run_id FROM personal_mission_task_attempt WHERE mission_id=? AND task_id=? AND run_id IS NOT NULL ORDER BY attempt_no DESC LIMIT 1")) {
            statement.setString(1, missionId);
            statement.setString(2, taskId);
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getString(1)) : Optional.empty();
            }
        }
    }

    private MissionTaskAttempt requireAttempt(String missionId, String taskId, int attemptNo) {
        try (var statement = current()
                .prepareStatement(
                        "SELECT * FROM personal_mission_task_attempt WHERE mission_id=? AND task_id=? AND attempt_no=?")) {
            statement.setString(1, missionId);
            statement.setString(2, taskId);
            statement.setInt(3, attemptNo);
            try (var result = statement.executeQuery()) {
                if (!result.next())
                    throw new MissionException("MISSION_ATTEMPT_NOT_FOUND", "Task attempt is unavailable");
                return readAttempt(result);
            }
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    private MissionTaskAttempt readAttempt(ResultSet result) throws SQLException {
        return new MissionTaskAttempt(
                result.getString("mission_id"),
                result.getString("task_id"),
                result.getInt("attempt_no"),
                result.getString("attempt_kind"),
                result.getString("dispatch_key"),
                result.getString("dispatch_payload_digest"),
                enumValue(MissionTaskAttemptState.class, result.getString("state"), "attempt state"),
                Optional.ofNullable(result.getString("session_id")),
                Optional.ofNullable(result.getString("run_id")),
                Optional.ofNullable(result.getString("result_digest")),
                Optional.ofNullable(result.getString("failure_code")),
                result.getLong("version"),
                Instant.ofEpochMilli(result.getLong("created_at_ms")),
                Instant.ofEpochMilli(result.getLong("updated_at_ms")),
                optionalInstant(result, "started_at_ms"),
                optionalInstant(result, "settled_at_ms"));
    }

    private void completeOutbox(String outboxId, Instant now) {
        try (var statement = current()
                .prepareStatement(
                        "UPDATE personal_mission_outbox SET state='COMPLETED',completed_at_ms=? WHERE outbox_id=?")) {
            statement.setLong(1, now.toEpochMilli());
            statement.setString(2, outboxId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    private void updateMissionState(String missionId, String state, Instant now, String predicate) throws SQLException {
        try (var statement = current()
                .prepareStatement(
                        "UPDATE personal_mission SET state=?,updated_at_ms=?,version=version+1 WHERE mission_id=? AND "
                                + predicate)) {
            statement.setString(1, state);
            statement.setLong(2, now.toEpochMilli());
            statement.setString(3, missionId);
            statement.executeUpdate();
        }
    }

    private void touchMission(String missionId, Instant now) throws SQLException {
        try (var statement = current()
                .prepareStatement("UPDATE personal_mission SET updated_at_ms=?,version=version+1 WHERE mission_id=?")) {
            statement.setLong(1, now.toEpochMilli());
            statement.setString(2, missionId);
            statement.executeUpdate();
        }
    }

    private PersonalMission readMission(ResultSet result) throws SQLException {
        String missionId = result.getString("mission_id");
        List<MissionPlanRevision> revisions = readRevisions(missionId);
        MissionConstraintsPayload constraints =
                json(result.getString("constraints_json"), MissionConstraintsPayload.class, "constraints");
        PersonalMission.Persistence value = new PersonalMission.Persistence(
                missionId,
                result.getString("conversation_id"),
                result.getString("owner_scope"),
                result.getString("objective"),
                jsonList(result.getString("acceptance_json")),
                new MissionConstraints(
                        constraints.maxTasks(),
                        constraints.maxDependencyDepth(),
                        Optional.ofNullable(constraints.deadlineAtMs()).map(Instant::ofEpochMilli)),
                Optional.ofNullable(result.getString("selected_skill_id")),
                Optional.ofNullable(result.getString("selected_skill_binding")),
                enumValue(
                        io.haifa.agent.personalassistant.application.mission.MissionMode.class,
                        result.getString("mode"),
                        "Mission mode"),
                Optional.ofNullable(result.getString("research_brief_json"))
                        .map(encoded -> json(
                                encoded,
                                io.haifa.agent.personalassistant.application.mission.ResearchBrief.class,
                                "research brief")),
                enumValue(MissionState.class, result.getString("state"), "Mission state"),
                optionalInteger(result, "active_plan_revision_no"),
                optionalInteger(result, "confirmed_plan_revision_no"),
                Optional.ofNullable(result.getString("failure_code")),
                result.getLong("version"),
                Instant.ofEpochMilli(result.getLong("created_at_ms")),
                Instant.ofEpochMilli(result.getLong("updated_at_ms")),
                optionalInstant(result, "confirmed_at_ms"),
                optionalInstant(result, "finished_at_ms"),
                revisions);
        return PersonalMission.reconstitute(value);
    }

    private List<MissionPlanRevision> readRevisions(String missionId) throws SQLException {
        try (var statement = current()
                .prepareStatement(
                        "SELECT * FROM personal_mission_plan_revision WHERE mission_id=? ORDER BY revision_no")) {
            statement.setString(1, missionId);
            try (var result = statement.executeQuery()) {
                List<MissionPlanRevision> values = new ArrayList<>();
                while (result.next()) {
                    PlanPayload payload = json(result.getString("plan_json"), PlanPayload.class, "plan");
                    values.add(new MissionPlanRevision(
                            result.getInt("revision_no"),
                            result.getString("schema_id"),
                            result.getString("schema_version"),
                            payload.tasks(),
                            result.getString("plan_digest"),
                            Optional.ofNullable(result.getString("planner_session_id")),
                            Optional.ofNullable(result.getString("planner_run_id")),
                            Instant.ofEpochMilli(result.getLong("created_at_ms")),
                            optionalInstant(result, "confirmed_at_ms")));
                }
                return List.copyOf(values);
            }
        }
    }

    private Optional<MissionCommandBinding> findCommand(String ownerScope, String operation, String key)
            throws SQLException {
        try (var statement = current()
                .prepareStatement(
                        "SELECT * FROM personal_mission_command WHERE owner_scope=? AND operation=? AND idempotency_key=?")) {
            statement.setString(1, ownerScope);
            statement.setString(2, operation);
            statement.setString(3, key);
            try (var result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new MissionCommandBinding(
                        ownerScope,
                        operation,
                        key,
                        result.getString("request_digest"),
                        result.getString("mission_id"),
                        Instant.ofEpochMilli(result.getLong("created_at_ms"))));
            }
        }
    }

    private void persistRevisions(PersonalMission.Persistence mission) throws SQLException {
        for (MissionPlanRevision revision : mission.revisions()) {
            try (var insert = current()
                    .prepareStatement(
                            """
                    INSERT OR IGNORE INTO personal_mission_plan_revision(
                        mission_id, revision_no, schema_id, schema_version, plan_json, plan_digest,
                        planner_session_id, planner_run_id, created_at_ms, confirmed_at_ms
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                insert.setString(1, mission.missionId());
                insert.setInt(2, revision.revisionNo());
                insert.setString(3, revision.schemaId());
                insert.setString(4, revision.schemaVersion());
                insert.setString(5, json(new PlanPayload(revision.tasks())));
                insert.setString(6, revision.planDigest());
                nullableString(insert, 7, revision.plannerSessionId());
                nullableString(insert, 8, revision.plannerRunId());
                insert.setLong(9, revision.createdAt().toEpochMilli());
                nullableInstant(insert, 10, revision.confirmedAt());
                insert.executeUpdate();
            }
            if (revision.confirmedAt().isPresent()) {
                try (var confirm = current()
                        .prepareStatement(
                                """
                        UPDATE personal_mission_plan_revision SET confirmed_at_ms=?
                        WHERE mission_id=? AND revision_no=? AND confirmed_at_ms IS NULL
                        """)) {
                    confirm.setLong(1, revision.confirmedAt().orElseThrow().toEpochMilli());
                    confirm.setString(2, mission.missionId());
                    confirm.setInt(3, revision.revisionNo());
                    confirm.executeUpdate();
                }
            }
        }
    }

    private void replaceActiveTasks(PersonalMission.Persistence mission) throws SQLException {
        if (mission.activePlanRevisionNo().isEmpty()) return;
        try (var dependencies =
                        current().prepareStatement("DELETE FROM personal_mission_task_dependency WHERE mission_id=?");
                var tasks = current().prepareStatement("DELETE FROM personal_mission_task WHERE mission_id=?")) {
            dependencies.setString(1, mission.missionId());
            dependencies.executeUpdate();
            tasks.setString(1, mission.missionId());
            tasks.executeUpdate();
        }
        MissionPlanRevision plan = mission.revisions().stream()
                .filter(value ->
                        value.revisionNo() == mission.activePlanRevisionNo().orElseThrow())
                .findFirst()
                .orElseThrow();
        for (MissionTask task : plan.tasks()) {
            try (var insert = current()
                    .prepareStatement(
                            """
                    INSERT INTO personal_mission_task(
                        mission_id, task_id, plan_revision_no, ordinal, title, objective, acceptance_json,
                        task_type, skill_ids_json, result_schema_id, result_schema_version, state,
                        created_at_ms, updated_at_ms
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                insert.setString(1, mission.missionId());
                insert.setString(2, task.taskId());
                insert.setInt(3, plan.revisionNo());
                insert.setInt(4, task.ordinal());
                insert.setString(5, task.title());
                insert.setString(6, task.objective());
                insert.setString(7, json(task.acceptanceCriteria()));
                insert.setString(8, task.taskType());
                insert.setString(
                        9, json(task.requiredSkillIds().stream().sorted().toList()));
                insert.setString(10, task.resultSchemaId());
                insert.setString(11, task.resultSchemaVersion());
                insert.setString(12, task.state().name());
                insert.setLong(13, plan.createdAt().toEpochMilli());
                insert.setLong(14, mission.updatedAt().toEpochMilli());
                insert.executeUpdate();
            }
        }
        for (MissionTask task : plan.tasks()) {
            for (String dependency : task.dependsOn()) {
                try (var insert = current()
                        .prepareStatement(
                                "INSERT INTO personal_mission_task_dependency(mission_id, task_id, depends_on_task_id) VALUES (?, ?, ?)")) {
                    insert.setString(1, mission.missionId());
                    insert.setString(2, task.taskId());
                    insert.setString(3, dependency);
                    insert.executeUpdate();
                }
            }
        }
    }

    private void appendEvent(String missionId, String type, Instant at) throws SQLException {
        try (var statement = current()
                .prepareStatement(
                        "INSERT INTO personal_mission_event(mission_id,event_type,schema_version,payload_json,created_at_ms) VALUES (?,?,'v1','{}',?)")) {
            statement.setString(1, missionId);
            statement.setString(2, type);
            statement.setLong(3, at.toEpochMilli());
            statement.executeUpdate();
        }
    }

    private void bindMission(java.sql.PreparedStatement statement, PersonalMission.Persistence value)
            throws SQLException {
        statement.setString(1, value.missionId());
        statement.setString(2, value.conversationId());
        statement.setString(3, value.ownerScope());
        statement.setString(4, value.objective());
        statement.setString(5, json(value.acceptanceCriteria()));
        statement.setString(
                6,
                json(new MissionConstraintsPayload(
                        value.constraints().maxTasks(),
                        value.constraints().maxDependencyDepth(),
                        value.constraints()
                                .deadlineAt()
                                .map(Instant::toEpochMilli)
                                .orElse(null))));
        nullableString(statement, 7, value.selectedSkillId());
        nullableString(statement, 8, value.selectedSkillBinding());
        statement.setString(9, value.mode().name());
        if (value.researchBrief().isPresent())
            statement.setString(10, json(value.researchBrief().orElseThrow()));
        else statement.setNull(10, java.sql.Types.VARCHAR);
        statement.setString(11, value.state().name());
        nullableInteger(statement, 12, value.activePlanRevisionNo());
        nullableInteger(statement, 13, value.confirmedPlanRevisionNo());
        nullableString(statement, 14, value.failureCode());
        statement.setLong(15, value.version());
        statement.setLong(16, value.createdAt().toEpochMilli());
        statement.setLong(17, value.updatedAt().toEpochMilli());
        nullableInstant(statement, 18, value.confirmedAt());
        nullableInstant(statement, 19, value.finishedAt());
        statement.setLong(
                20,
                value.constraints()
                        .deadlineAt()
                        .orElse(value.createdAt().plusSeconds(30 * 60L))
                        .toEpochMilli());
    }

    private Connection current() {
        Connection connection = transaction.get();
        if (connection == null) throw new IllegalStateException("Mission Store call requires MissionUnitOfWork");
        return connection;
    }

    private static void configure(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        }
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static void executeControl(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void rollbackControl(Connection connection, Throwable failure) {
        try {
            executeControl(connection, "ROLLBACK");
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new MissionException("MISSION_CODEC_FAILED", "Mission value cannot be encoded", exception);
        }
    }

    private <T> T json(String value, Class<T> type, String field) {
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new MissionException("MISSION_CODEC_FAILED", "Mission " + field + " cannot be decoded", exception);
        }
    }

    private List<String> jsonList(String value) {
        try {
            return List.copyOf(
                    mapper.readValue(value, mapper.getTypeFactory().constructCollectionType(List.class, String.class)));
        } catch (JsonProcessingException exception) {
            throw new MissionException("MISSION_CODEC_FAILED", "Mission text list cannot be decoded", exception);
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new MissionException("MISSION_CODEC_FAILED", field + " is unsupported", exception);
        }
    }

    private static Optional<Integer> optionalInteger(ResultSet result, String column) throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? Optional.empty() : Optional.of(value);
    }

    private static Optional<Instant> optionalInstant(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? Optional.empty() : Optional.of(Instant.ofEpochMilli(value));
    }

    private static void nullableString(java.sql.PreparedStatement statement, int index, Optional<String> value)
            throws SQLException {
        if (value.isPresent()) statement.setString(index, value.orElseThrow());
        else statement.setNull(index, java.sql.Types.VARCHAR);
    }

    private static void nullableInteger(java.sql.PreparedStatement statement, int index, Optional<Integer> value)
            throws SQLException {
        if (value.isPresent()) statement.setInt(index, value.orElseThrow());
        else statement.setNull(index, java.sql.Types.INTEGER);
    }

    private static void nullableInstant(java.sql.PreparedStatement statement, int index, Optional<Instant> value)
            throws SQLException {
        if (value.isPresent()) statement.setLong(index, value.orElseThrow().toEpochMilli());
        else statement.setNull(index, java.sql.Types.BIGINT);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static MissionException constraint(SQLException exception) {
        String message =
                exception.getMessage() == null ? "" : exception.getMessage().toUpperCase(Locale.ROOT);
        if (message.contains("UQ_PERSONAL_MISSION_ACTIVE_CONVERSATION")
                || message.contains("UNIQUE CONSTRAINT FAILED: PERSONAL_MISSION.CONVERSATION_ID")) {
            return new MissionException(
                    "MISSION_ACTIVE_EXISTS", "Conversation already has an active Mission", exception);
        }
        if (message.contains("MISSION_PLAN_FROZEN")) {
            return new MissionException("MISSION_PLAN_FROZEN", "confirmed Mission plan cannot be changed", exception);
        }
        return failure(exception);
    }

    private static MissionException failure(SQLException exception) {
        return new MissionException("MISSION_STORE_FAILED", "Personal Mission persistence failed", exception);
    }

    private record MissionConstraintsPayload(int maxTasks, int maxDependencyDepth, Long deadlineAtMs) {}

    private record PlanPayload(List<MissionTask> tasks) {
        private PlanPayload {
            tasks = List.copyOf(tasks);
        }
    }

    private record DispatcherHealth(String status, boolean recovering) {}
}

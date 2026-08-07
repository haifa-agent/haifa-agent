package io.haifa.agent.personalassistant.server.mission;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.personalassistant.application.mission.MissionCommandBinding;
import io.haifa.agent.personalassistant.application.mission.MissionCommandReservation;
import io.haifa.agent.personalassistant.application.mission.MissionConstraints;
import io.haifa.agent.personalassistant.application.mission.MissionException;
import io.haifa.agent.personalassistant.application.mission.MissionListCursor;
import io.haifa.agent.personalassistant.application.mission.MissionPlanRevision;
import io.haifa.agent.personalassistant.application.mission.MissionState;
import io.haifa.agent.personalassistant.application.mission.MissionStore;
import io.haifa.agent.personalassistant.application.mission.MissionTask;
import io.haifa.agent.personalassistant.application.mission.MissionUnitOfWork;
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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/** Product-owned SQLite migration, Store and UoW. It deliberately does not modify public Runtime mappings. */
public final class SqliteMissionStore implements MissionStore, MissionUnitOfWork {
    private static final int SCHEMA_VERSION = 1;
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

    private final String jdbcUrl;
    private final ObjectMapper mapper;
    private final ThreadLocal<Connection> transaction = new ThreadLocal<>();

    public SqliteMissionStore(Path database, ObjectMapper mapper) {
        Path normalized = database.toAbsolutePath().normalize();
        try {
            Files.createDirectories(normalized.getParent());
        } catch (IOException exception) {
            throw new MissionException("MISSION_STORE_FAILED", "Mission data directory is unavailable", exception);
        }
        jdbcUrl = "jdbc:sqlite:" + normalized;
        this.mapper = mapper.copy();
        migrate();
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
                    selected_skill_id, state, active_plan_revision_no, confirmed_plan_revision_no, failure_code,
                    version, created_at_ms, updated_at_ms, confirmed_at_ms, finished_at_ms, deadline_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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

    private void migrate() {
        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
            configure(connection);
            try (var statement = connection.createStatement()) {
                statement.execute(
                        "CREATE TABLE IF NOT EXISTS personal_schema_history(version INTEGER PRIMARY KEY, checksum TEXT NOT NULL, installed_at_ms INTEGER NOT NULL)");
            }
            String checksum = sha256(MIGRATION);
            try (var query =
                    connection.prepareStatement("SELECT checksum FROM personal_schema_history WHERE version=?")) {
                query.setInt(1, SCHEMA_VERSION);
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
                for (String sql : SqlScriptParser.parse(MIGRATION)) {
                    try (var statement = connection.createStatement()) {
                        statement.execute(sql);
                    }
                }
                try (var insert = connection.prepareStatement(
                        "INSERT INTO personal_schema_history(version, checksum, installed_at_ms) VALUES (?, ?, ?)")) {
                    insert.setInt(1, SCHEMA_VERSION);
                    insert.setString(2, checksum);
                    insert.setLong(3, System.currentTimeMillis());
                    insert.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            }
        } catch (SQLException exception) {
            throw failure(exception);
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
        statement.setString(8, value.state().name());
        nullableInteger(statement, 9, value.activePlanRevisionNo());
        nullableInteger(statement, 10, value.confirmedPlanRevisionNo());
        nullableString(statement, 11, value.failureCode());
        statement.setLong(12, value.version());
        statement.setLong(13, value.createdAt().toEpochMilli());
        statement.setLong(14, value.updatedAt().toEpochMilli());
        nullableInstant(statement, 15, value.confirmedAt());
        nullableInstant(statement, 16, value.finishedAt());
        statement.setLong(
                17,
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
}

package io.haifa.agent.store.sqlite;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.orchestration.api.WorkflowCheckpoint;
import io.haifa.agent.orchestration.api.WorkflowCheckpointId;
import io.haifa.agent.orchestration.api.WorkflowDefinitionDigest;
import io.haifa.agent.orchestration.api.WorkflowDefinitionId;
import io.haifa.agent.orchestration.api.WorkflowDefinitionRef;
import io.haifa.agent.orchestration.api.WorkflowDefinitionVersion;
import io.haifa.agent.orchestration.api.WorkflowErrorCode;
import io.haifa.agent.orchestration.api.WorkflowEvent;
import io.haifa.agent.orchestration.api.WorkflowEventType;
import io.haifa.agent.orchestration.api.WorkflowException;
import io.haifa.agent.orchestration.api.WorkflowFailure;
import io.haifa.agent.orchestration.api.WorkflowNodeAttempt;
import io.haifa.agent.orchestration.api.WorkflowNodeAttemptStatus;
import io.haifa.agent.orchestration.api.WorkflowNodeId;
import io.haifa.agent.orchestration.api.WorkflowParentLink;
import io.haifa.agent.orchestration.api.WorkflowRunId;
import io.haifa.agent.orchestration.api.WorkflowRunSnapshot;
import io.haifa.agent.orchestration.api.WorkflowStateDelta;
import io.haifa.agent.orchestration.api.WorkflowStatus;
import io.haifa.agent.orchestration.api.WorkflowSubgraphLink;
import io.haifa.agent.orchestration.api.WorkflowWait;
import io.haifa.agent.orchestration.api.WorkflowWaitId;
import io.haifa.agent.orchestration.core.spi.StoredWorkflowCommand;
import io.haifa.agent.orchestration.core.spi.StoredWorkflowRun;
import io.haifa.agent.orchestration.core.spi.WorkflowOutboxRecord;
import io.haifa.agent.orchestration.core.spi.WorkflowPersistenceBinding;
import io.haifa.agent.orchestration.core.spi.WorkflowStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Optional SQLite V12/V13 authoritative Workflow store. No Graph-provider object is serialized. */
public final class SqliteWorkflowStore implements WorkflowStore {
    private final SqliteRuntimeUnitOfWork unitOfWork;
    private final SqliteWorkflowCodec codec;

    public SqliteWorkflowStore(SqliteRuntimeUnitOfWork unitOfWork, int maximumPayloadBytes) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        this.codec = new SqliteWorkflowCodec(maximumPayloadBytes);
    }

    @Override
    public Optional<StoredWorkflowRun> find(WorkflowRunId runId) {
        Objects.requireNonNull(runId, "runId must not be null");
        return read(() -> findInTransaction(runId));
    }

    @Override
    public List<StoredWorkflowRun> recoverable() {
        return read(() -> {
            List<StoredWorkflowRun> runs = new ArrayList<>();
            try (PreparedStatement statement = connection()
                    .prepareStatement("SELECT workflow_run_id FROM workflow_run WHERE status IN ('RUNNING','WAITING') "
                            + "ORDER BY updated_at, workflow_run_id")) {
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        runs.add(findInTransaction(new WorkflowRunId(rows.getString(1)))
                                .orElseThrow());
                    }
                }
                return List.copyOf(runs);
            } catch (SQLException exception) {
                throw failure("Unable to list recoverable Workflow runs", exception);
            }
        });
    }

    @Override
    public Optional<StoredWorkflowCommand> findCommand(String operation, String scope, String idempotencyKeyDigest) {
        return read(() -> {
            try (PreparedStatement statement = connection()
                    .prepareStatement("SELECT request_digest, workflow_run_id, result_payload, result_hash "
                            + "FROM workflow_command WHERE operation=? AND command_scope=? "
                            + "AND idempotency_key_digest=?")) {
                statement.setString(1, operation);
                statement.setString(2, scope);
                statement.setString(3, idempotencyKeyDigest);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) return Optional.empty();
                    return Optional.of(new StoredWorkflowCommand(
                            operation,
                            scope,
                            idempotencyKeyDigest,
                            row.getString("request_digest"),
                            new WorkflowRunId(row.getString("workflow_run_id")),
                            codec.decodeSnapshot(row.getBytes("result_payload"), row.getString("result_hash"))));
                }
            } catch (SQLException exception) {
                throw failure("Unable to read Workflow command", exception);
            }
        });
    }

    @Override
    public void create(StoredWorkflowRun run, StoredWorkflowCommand startCommand, List<WorkflowEvent> events) {
        write(() -> {
            unitOfWork.currentSession().flushStatements();
            insertRun(run);
            synchronizeSubgraphRelation(run.snapshot());
            replaceChildren(run);
            appendEvents(events);
            upsertCommand(startCommand);
            return null;
        });
    }

    @Override
    public void save(
            long expectedStorageVersion,
            StoredWorkflowRun run,
            List<WorkflowEvent> events,
            Optional<StoredWorkflowCommand> command) {
        write(() -> {
            unitOfWork.currentSession().flushStatements();
            updateRun(expectedStorageVersion, run);
            synchronizeSubgraphRelation(run.snapshot());
            replaceChildren(run);
            appendEvents(events);
            command.ifPresent(this::upsertCommand);
            return null;
        });
    }

    @Override
    public List<WorkflowEvent> events(WorkflowRunId runId, long afterSequence, int limit) {
        return read(() -> {
            List<WorkflowEvent> events = new ArrayList<>();
            try (PreparedStatement statement = connection()
                    .prepareStatement("SELECT sequence,type,node_id,attributes_payload,attributes_hash,occurred_at "
                            + "FROM workflow_event WHERE workflow_run_id=? AND sequence>? "
                            + "ORDER BY sequence LIMIT ?")) {
                statement.setString(1, runId.value());
                statement.setLong(2, afterSequence);
                statement.setInt(3, limit);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) events.add(event(runId, rows));
                }
                return List.copyOf(events);
            } catch (SQLException exception) {
                throw failure("Unable to read Workflow events", exception);
            }
        });
    }

    @Override
    public List<WorkflowOutboxRecord> pendingOutbox(int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        return read(() -> {
            List<WorkflowOutboxRecord> records = new ArrayList<>();
            try (PreparedStatement statement = connection()
                    .prepareStatement(
                            "SELECT e.workflow_run_id,e.sequence,e.type,e.node_id,e.attributes_payload,e.attributes_hash,"
                                    + "e.occurred_at,o.published_at FROM workflow_outbox o JOIN workflow_event e "
                                    + "ON e.workflow_run_id=o.workflow_run_id AND e.sequence=o.sequence "
                                    + "WHERE o.published_at IS NULL ORDER BY e.workflow_run_id,e.sequence LIMIT ?")) {
                statement.setInt(1, limit);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        WorkflowEvent event = event(new WorkflowRunId(rows.getString("workflow_run_id")), rows);
                        records.add(new WorkflowOutboxRecord(event, optionalInstant(rows, "published_at")));
                    }
                }
                return List.copyOf(records);
            } catch (SQLException exception) {
                throw failure("Unable to read Workflow outbox", exception);
            }
        });
    }

    @Override
    public void markOutboxPublished(WorkflowRunId runId, long sequence, Instant publishedAt) {
        write(() -> {
            try (PreparedStatement statement = connection()
                    .prepareStatement("UPDATE workflow_outbox SET published_at=COALESCE(published_at,?), "
                            + "publish_attempts=publish_attempts+1 WHERE workflow_run_id=? AND sequence=?")) {
                statement.setLong(1, publishedAt.toEpochMilli());
                statement.setString(2, runId.value());
                statement.setLong(3, sequence);
                if (statement.executeUpdate() != 1) throw conflict("workflow outbox event does not exist");
                return null;
            } catch (SQLException exception) {
                throw failure("Unable to publish Workflow outbox", exception);
            }
        });
    }

    private Optional<StoredWorkflowRun> findInTransaction(WorkflowRunId runId) {
        try (PreparedStatement statement =
                connection().prepareStatement("SELECT * FROM workflow_run WHERE workflow_run_id=?")) {
            statement.setString(1, runId.value());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                var state = codec.decodeState(row.getBytes("state_payload"), row.getString("state_hash"));
                var control = codec.decodeControl(row.getBytes("control_payload"), row.getString("control_hash"));
                List<WorkflowNodeAttempt> attempts = readAttempts(runId);
                Optional<WorkflowStateDelta> pendingDelta = readPendingDelta(runId);
                Optional<WorkflowWait> wait = readWait(runId);
                Optional<WorkflowCheckpoint> checkpoint = readCheckpoint(runId);
                Optional<WorkflowFailure> failure = Optional.ofNullable(row.getString("failure_code"))
                        .map(code -> new WorkflowFailure(
                                WorkflowErrorCode.valueOf(code),
                                getString(row, "failure_operation"),
                                Optional.ofNullable(getString(row, "failure_node_id"))
                                        .map(WorkflowNodeId::new)));
                WorkflowDefinitionRef definition = new WorkflowDefinitionRef(
                        new WorkflowDefinitionId(row.getString("definition_id")),
                        new WorkflowDefinitionVersion(row.getLong("definition_version")),
                        new WorkflowDefinitionDigest(row.getString("definition_digest")));
                WorkflowRunSnapshot snapshot = new WorkflowRunSnapshot(
                        runId,
                        definition,
                        WorkflowStatus.valueOf(row.getString("status")),
                        row.getLong("revision"),
                        state,
                        Optional.ofNullable(row.getString("current_node_id")).map(WorkflowNodeId::new),
                        wait,
                        checkpoint,
                        failure,
                        attempts,
                        readParentLink(runId),
                        readActiveSubgraph(runId),
                        Instant.ofEpochMilli(row.getLong("created_at")),
                        Instant.ofEpochMilli(row.getLong("updated_at")));
                WorkflowPersistenceBinding binding = new WorkflowPersistenceBinding(
                        row.getString("adapter_coordinate"),
                        row.getString("adapter_version"),
                        row.getString("adapter_configuration_digest"),
                        row.getInt("state_codec_version"));
                return Optional.of(new StoredWorkflowRun(
                        snapshot,
                        binding,
                        row.getLong("storage_version"),
                        row.getLong("event_sequence"),
                        control.visits(),
                        control.consumedSignals(),
                        pendingDelta,
                        control.forkState(),
                        control.pendingAgentCancellation()));
            }
        } catch (SQLException exception) {
            throw failure("Unable to read Workflow run", exception);
        }
    }

    private Optional<WorkflowParentLink> readParentLink(WorkflowRunId childRunId) throws SQLException {
        try (PreparedStatement statement = connection()
                .prepareStatement("SELECT parent_workflow_run_id,parent_node_id,parent_node_attempt "
                        + "FROM workflow_subgraph_instance WHERE child_workflow_run_id=?")) {
            statement.setString(1, childRunId.value());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                return Optional.of(new WorkflowParentLink(
                        new WorkflowRunId(row.getString("parent_workflow_run_id")),
                        new WorkflowNodeId(row.getString("parent_node_id")),
                        row.getInt("parent_node_attempt")));
            }
        }
    }

    private Optional<WorkflowSubgraphLink> readActiveSubgraph(WorkflowRunId parentRunId) throws SQLException {
        try (PreparedStatement statement = connection()
                .prepareStatement("SELECT s.child_workflow_run_id,s.parent_node_id,s.parent_node_attempt,"
                        + "r.definition_id,r.definition_version,r.definition_digest "
                        + "FROM workflow_subgraph_instance s JOIN workflow_run r "
                        + "ON r.workflow_run_id=s.child_workflow_run_id "
                        + "WHERE s.parent_workflow_run_id=? AND s.active=1")) {
            statement.setString(1, parentRunId.value());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                WorkflowSubgraphLink link = new WorkflowSubgraphLink(
                        new WorkflowRunId(row.getString("child_workflow_run_id")),
                        new WorkflowDefinitionRef(
                                new WorkflowDefinitionId(row.getString("definition_id")),
                                new WorkflowDefinitionVersion(row.getLong("definition_version")),
                                new WorkflowDefinitionDigest(row.getString("definition_digest"))),
                        new WorkflowNodeId(row.getString("parent_node_id")),
                        row.getInt("parent_node_attempt"));
                if (row.next()) throw conflict("workflow run has multiple active subgraphs");
                return Optional.of(link);
            }
        }
    }

    private void synchronizeSubgraphRelation(WorkflowRunSnapshot snapshot) {
        snapshot.parent().ifPresent(parent -> {
            try (PreparedStatement statement = connection()
                    .prepareStatement("INSERT INTO workflow_subgraph_instance(child_workflow_run_id,"
                            + "parent_workflow_run_id,parent_node_id,parent_node_attempt,active) VALUES(?,?,?,?,1) "
                            + "ON CONFLICT(child_workflow_run_id) DO UPDATE SET active=workflow_subgraph_instance.active "
                            + "WHERE parent_workflow_run_id=excluded.parent_workflow_run_id "
                            + "AND parent_node_id=excluded.parent_node_id "
                            + "AND parent_node_attempt=excluded.parent_node_attempt")) {
                statement.setString(1, snapshot.id().value());
                statement.setString(2, parent.runId().value());
                statement.setString(3, parent.nodeId().value());
                statement.setInt(4, parent.nodeAttempt());
                if (statement.executeUpdate() != 1) {
                    throw conflict("Workflow subgraph parent relation is immutable");
                }
            } catch (SQLException exception) {
                throw failure("Unable to persist Workflow subgraph parent relation", exception);
            }
        });

        boolean keepActive =
                !snapshot.status().terminal() && snapshot.activeSubgraph().isPresent();
        try (PreparedStatement clear = connection()
                .prepareStatement("UPDATE workflow_subgraph_instance SET active=0 WHERE parent_workflow_run_id=?")) {
            clear.setString(1, snapshot.id().value());
            clear.executeUpdate();
        } catch (SQLException exception) {
            throw failure("Unable to clear Workflow subgraph activity", exception);
        }
        if (!keepActive) return;
        WorkflowSubgraphLink active = snapshot.activeSubgraph().orElseThrow();
        try (PreparedStatement statement = connection()
                .prepareStatement("UPDATE workflow_subgraph_instance SET active=1 "
                        + "WHERE child_workflow_run_id=? AND parent_workflow_run_id=? "
                        + "AND parent_node_id=? AND parent_node_attempt=?")) {
            statement.setString(1, active.runId().value());
            statement.setString(2, snapshot.id().value());
            statement.setString(3, active.parentNodeId().value());
            statement.setInt(4, active.parentNodeAttempt());
            if (statement.executeUpdate() != 1) {
                throw conflict("active Workflow subgraph relation does not exist");
            }
        } catch (SQLException exception) {
            throw failure("Unable to persist active Workflow subgraph relation", exception);
        }
    }

    private void insertRun(StoredWorkflowRun run) {
        WorkflowRunSnapshot snapshot = run.snapshot();
        SqliteWorkflowCodec.Encoded state = codec.encodeState(snapshot.state());
        SqliteWorkflowCodec.Encoded control = codec.encodeControl(
                run.nodeVisits(), run.consumedSignalIds(), run.forkState(), run.pendingAgentCancellation());
        try (PreparedStatement statement = connection()
                .prepareStatement(
                        "INSERT INTO workflow_run(workflow_run_id,definition_id,definition_version,definition_digest,"
                                + "adapter_coordinate,adapter_version,adapter_configuration_digest,state_codec_version,"
                                + "status,revision,storage_version,event_sequence,current_node_id,state_schema_version,"
                                + "state_payload,state_hash,control_schema_version,control_payload,control_hash,"
                                + "failure_code,failure_operation,failure_node_id,created_at,updated_at) "
                                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            bindRun(statement, run, state, control, false);
            if (statement.executeUpdate() != 1) throw conflict("workflow run insert failed");
        } catch (SQLException exception) {
            throw failure("Unable to insert Workflow run", exception);
        }
    }

    private void updateRun(long expectedStorageVersion, StoredWorkflowRun run) {
        WorkflowRunSnapshot snapshot = run.snapshot();
        SqliteWorkflowCodec.Encoded state = codec.encodeState(snapshot.state());
        SqliteWorkflowCodec.Encoded control = codec.encodeControl(
                run.nodeVisits(), run.consumedSignalIds(), run.forkState(), run.pendingAgentCancellation());
        try (PreparedStatement statement = connection()
                .prepareStatement("UPDATE workflow_run SET definition_id=?,definition_version=?,definition_digest=?,"
                        + "adapter_coordinate=?,adapter_version=?,adapter_configuration_digest=?,state_codec_version=?,"
                        + "status=?,revision=?,storage_version=?,event_sequence=?,current_node_id=?,"
                        + "state_schema_version=?,state_payload=?,state_hash=?,control_schema_version=?,control_payload=?,"
                        + "control_hash=?,failure_code=?,failure_operation=?,failure_node_id=?,created_at=?,updated_at=? "
                        + "WHERE workflow_run_id=? AND storage_version=?")) {
            bindRun(statement, run, state, control, true);
            statement.setLong(25, expectedStorageVersion);
            if (statement.executeUpdate() != 1) throw conflict("workflow run storage version is stale");
        } catch (SQLException exception) {
            throw failure("Unable to update Workflow run", exception);
        }
    }

    private static void bindRun(
            PreparedStatement statement,
            StoredWorkflowRun run,
            SqliteWorkflowCodec.Encoded state,
            SqliteWorkflowCodec.Encoded control,
            boolean update)
            throws SQLException {
        WorkflowRunSnapshot snapshot = run.snapshot();
        WorkflowPersistenceBinding binding = run.binding();
        int index = 1;
        if (!update) statement.setString(index++, snapshot.id().value());
        statement.setString(index++, snapshot.definition().id().value());
        statement.setLong(index++, snapshot.definition().version().value());
        statement.setString(index++, snapshot.definition().digest().value());
        statement.setString(index++, binding.adapterCoordinate());
        statement.setString(index++, binding.adapterVersion());
        statement.setString(index++, binding.adapterConfigurationDigest());
        statement.setInt(index++, binding.stateCodecVersion());
        statement.setString(index++, snapshot.status().name());
        statement.setLong(index++, snapshot.revision());
        statement.setLong(index++, run.storageVersion());
        statement.setLong(index++, run.eventSequence());
        statement.setString(
                index++, snapshot.currentNode().map(WorkflowNodeId::value).orElse(null));
        statement.setInt(index++, SqliteWorkflowCodec.VERSION);
        statement.setBytes(index++, state.payload());
        statement.setString(index++, state.hash());
        statement.setInt(index++, SqliteWorkflowCodec.VERSION);
        statement.setBytes(index++, control.payload());
        statement.setString(index++, control.hash());
        statement.setString(
                index++, snapshot.failure().map(value -> value.code().name()).orElse(null));
        statement.setString(
                index++, snapshot.failure().map(WorkflowFailure::operation).orElse(null));
        statement.setString(
                index++,
                snapshot.failure()
                        .flatMap(WorkflowFailure::nodeId)
                        .map(WorkflowNodeId::value)
                        .orElse(null));
        statement.setLong(index++, snapshot.createdAt().toEpochMilli());
        statement.setLong(index++, snapshot.updatedAt().toEpochMilli());
        if (update) statement.setString(index, snapshot.id().value());
    }

    private void replaceChildren(StoredWorkflowRun run) {
        delete(
                "DELETE FROM workflow_node_attempt WHERE workflow_run_id=?",
                run.snapshot().id().value());
        delete(
                "DELETE FROM workflow_wait WHERE workflow_run_id=?",
                run.snapshot().id().value());
        delete(
                "DELETE FROM workflow_checkpoint WHERE workflow_run_id=?",
                run.snapshot().id().value());
        for (int index = 0; index < run.snapshot().attempts().size(); index++) {
            insertAttempt(run, run.snapshot().attempts().get(index), index + 1);
        }
        run.snapshot().activeWait().ifPresent(wait -> insertWait(run.snapshot().id(), wait));
        run.snapshot().checkpoint().ifPresent(this::insertCheckpoint);
    }

    private void insertAttempt(StoredWorkflowRun run, WorkflowNodeAttempt attempt, int sequence) {
        Optional<WorkflowStateDelta> pending =
                attempt.status() == WorkflowNodeAttemptStatus.RUNNING ? run.pendingDelta() : Optional.empty();
        Optional<SqliteWorkflowCodec.Encoded> encoded = pending.map(codec::encodeDelta);
        try (PreparedStatement statement = connection()
                .prepareStatement(
                        "INSERT INTO workflow_node_attempt(workflow_run_id,attempt_sequence,node_id,attempt,status,"
                                + "agent_run_id,failure_code,started_at,finished_at,pending_delta_schema_version,"
                                + "pending_delta_payload,pending_delta_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, run.snapshot().id().value());
            statement.setInt(2, sequence);
            statement.setString(3, attempt.nodeId().value());
            statement.setInt(4, attempt.attempt());
            statement.setString(5, attempt.status().name());
            statement.setString(6, attempt.agentRunId().map(AgentRunId::value).orElse(null));
            statement.setString(7, attempt.failureCode().map(Enum::name).orElse(null));
            statement.setLong(8, attempt.startedAt().toEpochMilli());
            if (attempt.finishedAt().isPresent())
                statement.setLong(9, attempt.finishedAt().orElseThrow().toEpochMilli());
            else statement.setNull(9, java.sql.Types.INTEGER);
            if (encoded.isPresent()) {
                statement.setInt(10, SqliteWorkflowCodec.VERSION);
                statement.setBytes(11, encoded.orElseThrow().payload());
                statement.setString(12, encoded.orElseThrow().hash());
            } else {
                statement.setNull(10, java.sql.Types.INTEGER);
                statement.setNull(11, java.sql.Types.BLOB);
                statement.setNull(12, java.sql.Types.VARCHAR);
            }
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure("Unable to insert Workflow attempt", exception);
        }
    }

    private List<WorkflowNodeAttempt> readAttempts(WorkflowRunId runId) throws SQLException {
        List<WorkflowNodeAttempt> attempts = new ArrayList<>();
        try (PreparedStatement statement = connection()
                .prepareStatement(
                        "SELECT * FROM workflow_node_attempt WHERE workflow_run_id=? ORDER BY attempt_sequence")) {
            statement.setString(1, runId.value());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    attempts.add(new WorkflowNodeAttempt(
                            new WorkflowNodeId(rows.getString("node_id")),
                            rows.getInt("attempt"),
                            WorkflowNodeAttemptStatus.valueOf(rows.getString("status")),
                            Optional.ofNullable(rows.getString("agent_run_id")).map(AgentRunId::new),
                            Optional.ofNullable(rows.getString("failure_code")).map(WorkflowErrorCode::valueOf),
                            Instant.ofEpochMilli(rows.getLong("started_at")),
                            optionalInstant(rows, "finished_at")));
                }
            }
        }
        return List.copyOf(attempts);
    }

    private Optional<WorkflowStateDelta> readPendingDelta(WorkflowRunId runId) throws SQLException {
        try (PreparedStatement statement = connection()
                .prepareStatement("SELECT pending_delta_payload,pending_delta_hash FROM workflow_node_attempt "
                        + "WHERE workflow_run_id=? AND status='RUNNING'")) {
            statement.setString(1, runId.value());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next() || row.getBytes(1) == null) return Optional.empty();
                return Optional.of(codec.decodeDelta(row.getBytes(1), row.getString(2)));
            }
        }
    }

    private void insertWait(WorkflowRunId runId, WorkflowWait wait) {
        try (PreparedStatement statement = connection()
                .prepareStatement(
                        "INSERT INTO workflow_wait(workflow_run_id,wait_id,node_id,revision,created_at) VALUES(?,?,?,?,?)")) {
            statement.setString(1, runId.value());
            statement.setString(2, wait.id().value());
            statement.setString(3, wait.nodeId().value());
            statement.setLong(4, wait.revision());
            statement.setLong(5, wait.createdAt().toEpochMilli());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure("Unable to insert Workflow wait", exception);
        }
    }

    private Optional<WorkflowWait> readWait(WorkflowRunId runId) throws SQLException {
        try (PreparedStatement statement =
                connection().prepareStatement("SELECT * FROM workflow_wait WHERE workflow_run_id=?")) {
            statement.setString(1, runId.value());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                return Optional.of(new WorkflowWait(
                        new WorkflowWaitId(row.getString("wait_id")),
                        new WorkflowNodeId(row.getString("node_id")),
                        row.getLong("revision"),
                        Instant.ofEpochMilli(row.getLong("created_at"))));
            }
        }
    }

    private void insertCheckpoint(WorkflowCheckpoint checkpoint) {
        SqliteWorkflowCodec.Encoded state = codec.encodeState(checkpoint.state());
        try (PreparedStatement statement = connection()
                .prepareStatement(
                        "INSERT INTO workflow_checkpoint(checkpoint_id,workflow_run_id,revision,resume_node_id,"
                                + "state_schema_version,state_payload,state_hash,created_at) VALUES(?,?,?,?,?,?,?,?)")) {
            statement.setString(1, checkpoint.id().value());
            statement.setString(2, checkpoint.runId().value());
            statement.setLong(3, checkpoint.revision());
            statement.setString(4, checkpoint.resumeNode().value());
            statement.setInt(5, SqliteWorkflowCodec.VERSION);
            statement.setBytes(6, state.payload());
            statement.setString(7, state.hash());
            statement.setLong(8, checkpoint.createdAt().toEpochMilli());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure("Unable to insert Workflow checkpoint", exception);
        }
    }

    private Optional<WorkflowCheckpoint> readCheckpoint(WorkflowRunId runId) throws SQLException {
        try (PreparedStatement statement =
                connection().prepareStatement("SELECT * FROM workflow_checkpoint WHERE workflow_run_id=?")) {
            statement.setString(1, runId.value());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                return Optional.of(new WorkflowCheckpoint(
                        new WorkflowCheckpointId(row.getString("checkpoint_id")),
                        runId,
                        row.getLong("revision"),
                        new WorkflowNodeId(row.getString("resume_node_id")),
                        codec.decodeState(row.getBytes("state_payload"), row.getString("state_hash")),
                        Instant.ofEpochMilli(row.getLong("created_at"))));
            }
        }
    }

    private void appendEvents(List<WorkflowEvent> events) {
        for (WorkflowEvent event : events) {
            SqliteWorkflowCodec.Encoded attributes = codec.encodeStringMap(event.attributes());
            try (PreparedStatement statement = connection()
                    .prepareStatement(
                            "INSERT INTO workflow_event(workflow_run_id,sequence,type,node_id,attributes_schema_version,"
                                    + "attributes_payload,attributes_hash,occurred_at) VALUES(?,?,?,?,?,?,?,?)")) {
                statement.setString(1, event.runId().value());
                statement.setLong(2, event.sequence());
                statement.setString(3, event.type().name());
                statement.setString(4, event.nodeId().map(WorkflowNodeId::value).orElse(null));
                statement.setInt(5, SqliteWorkflowCodec.VERSION);
                statement.setBytes(6, attributes.payload());
                statement.setString(7, attributes.hash());
                statement.setLong(8, event.occurredAt().toEpochMilli());
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw failure("Unable to append Workflow event", exception);
            }
            try (PreparedStatement statement = connection()
                    .prepareStatement("INSERT INTO workflow_outbox(workflow_run_id,sequence) VALUES(?,?)")) {
                statement.setString(1, event.runId().value());
                statement.setLong(2, event.sequence());
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw failure("Unable to append Workflow outbox", exception);
            }
        }
    }

    private WorkflowEvent event(WorkflowRunId runId, ResultSet row) throws SQLException {
        return new WorkflowEvent(
                runId,
                row.getLong("sequence"),
                WorkflowEventType.valueOf(row.getString("type")),
                Optional.ofNullable(row.getString("node_id")).map(WorkflowNodeId::new),
                codec.decodeStringMap(row.getBytes("attributes_payload"), row.getString("attributes_hash")),
                Instant.ofEpochMilli(row.getLong("occurred_at")));
    }

    private void upsertCommand(StoredWorkflowCommand command) {
        SqliteWorkflowCodec.Encoded result = codec.encodeSnapshot(command.result());
        try (PreparedStatement statement = connection()
                .prepareStatement(
                        "INSERT INTO workflow_command(operation,command_scope,idempotency_key_digest,request_digest,"
                                + "workflow_run_id,result_schema_version,result_payload,result_hash) VALUES(?,?,?,?,?,?,?,?) "
                                + "ON CONFLICT(operation,command_scope,idempotency_key_digest) DO UPDATE SET "
                                + "workflow_run_id=excluded.workflow_run_id,result_schema_version=excluded.result_schema_version,"
                                + "result_payload=excluded.result_payload,result_hash=excluded.result_hash "
                                + "WHERE workflow_command.request_digest=excluded.request_digest")) {
            statement.setString(1, command.operation());
            statement.setString(2, command.scope());
            statement.setString(3, command.idempotencyKeyDigest());
            statement.setString(4, command.requestDigest());
            statement.setString(5, command.runId().value());
            statement.setInt(6, SqliteWorkflowCodec.VERSION);
            statement.setBytes(7, result.payload());
            statement.setString(8, result.hash());
            if (statement.executeUpdate() != 1) throw conflict("workflow command request digest differs");
        } catch (SQLException exception) {
            throw failure("Unable to persist Workflow command", exception);
        }
    }

    private void delete(String sql, String runId) {
        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            statement.setString(1, runId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure("Unable to replace Workflow child state", exception);
        }
    }

    private Connection connection() {
        return unitOfWork.currentConnection();
    }

    private <T> T read(java.util.function.Supplier<T> work) {
        return unitOfWork.isActive() ? work.get() : unitOfWork.executeReadOnly(work);
    }

    private <T> T write(java.util.function.Supplier<T> work) {
        return unitOfWork.execute(work);
    }

    private static Optional<Instant> optionalInstant(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? Optional.empty() : Optional.of(Instant.ofEpochMilli(value));
    }

    private static String getString(ResultSet row, String column) {
        try {
            return row.getString(column);
        } catch (SQLException exception) {
            throw failure("Unable to decode Workflow row", exception);
        }
    }

    private static WorkflowException conflict(String message) {
        return new WorkflowException(WorkflowErrorCode.PERSISTENCE_CONFLICT, "persist", message);
    }

    private static SqliteStoreException failure(String message, SQLException exception) {
        return new SqliteStoreException(SqliteStoreFailure.TRANSACTION_FAILED, message, exception);
    }
}

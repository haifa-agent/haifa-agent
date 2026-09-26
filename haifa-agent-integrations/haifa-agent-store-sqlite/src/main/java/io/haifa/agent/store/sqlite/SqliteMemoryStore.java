package io.haifa.agent.store.sqlite;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.memory.api.Memory;
import io.haifa.agent.memory.api.MemoryCursorCodec;
import io.haifa.agent.memory.api.MemoryDraft;
import io.haifa.agent.memory.api.MemoryId;
import io.haifa.agent.memory.api.MemoryKind;
import io.haifa.agent.memory.api.MemoryOperationException;
import io.haifa.agent.memory.api.MemoryPage;
import io.haifa.agent.memory.api.MemoryQuery;
import io.haifa.agent.memory.api.MemoryRepository;
import io.haifa.agent.memory.api.MemoryScope;
import io.haifa.agent.memory.api.MemoryScopeType;
import io.haifa.agent.memory.api.MemorySourceRef;
import io.haifa.agent.memory.api.MemorySourceType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Production SQLite Memory repository over the single authoritative {@code memory_record} table. Deleted rows keep
 * only identity, revision and {@code deleted_at} (content is erased) so late writes for the same subject can be
 * refused; {@code memory_scope_clear} keeps the clear watermark of each scope.
 */
public final class SqliteMemoryStore implements MemoryRepository {
    private static final String COLUMNS = "memory_id,tenant_id,owner_id,owner_type,scope_type,target_id,kind,"
            + "subject_key,content,source_type,source_id,revision,created_at,updated_at,deleted_at";
    private static final String SCOPE_MATCH =
            "tenant_id=? AND owner_id=? AND owner_type=? AND scope_type=? AND target_id=?";
    private static final int TEXT_SCAN_BATCH = 256;

    private final SqliteRuntimeUnitOfWork unitOfWork;
    private final int textScanBatch;

    public SqliteMemoryStore(SqliteRuntimeUnitOfWork unitOfWork) {
        this(unitOfWork, TEXT_SCAN_BATCH);
    }

    /** Test seam: a small text scan batch exercises multi-batch pagination without thousands of rows. */
    SqliteMemoryStore(SqliteRuntimeUnitOfWork unitOfWork, int textScanBatch) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        if (textScanBatch < 1) throw new IllegalArgumentException("textScanBatch must be positive");
        this.textScanBatch = textScanBatch;
    }

    @Override
    public Memory upsert(MemoryDraft draft, MemoryId newId, Instant now) {
        return write(() -> {
            Optional<Instant> watermark = clearWatermark(draft.scope());
            if (watermark.filter(draft::observedNoLaterThan).isPresent()) throw stale();
            Optional<Row> existing = selectOne(
                    "SELECT " + COLUMNS + " FROM memory_record WHERE " + SCOPE_MATCH + " AND kind=? AND subject_key=?",
                    (statement, index) -> {
                        int next = bindScope(statement, index, draft.scope());
                        statement.setString(next++, draft.kind().name());
                        statement.setString(next, draft.subjectKey());
                    });
            long millis = now.toEpochMilli();
            if (existing.isEmpty()) {
                insert(newId, draft, millis);
                return find(newId).orElseThrow();
            }
            Row row = existing.orElseThrow();
            if (row.deletedAt != null) {
                if (draft.observedNoLaterThan(Instant.ofEpochMilli(row.deletedAt))) throw stale();
                long at = Math.max(millis, row.updatedAt);
                execute(
                        "UPDATE memory_record SET content=?,source_type=?,source_id=?,revision=revision+1,"
                                + "created_at=?,updated_at=?,deleted_at=NULL WHERE memory_id=?",
                        (statement, index) -> {
                            int next = bindContent(statement, index, draft.content(), draft.source());
                            statement.setLong(next++, at);
                            statement.setLong(next++, at);
                            statement.setString(next, row.id);
                        });
                return find(new MemoryId(row.id)).orElseThrow();
            }
            Memory live = row.memory();
            if (live.sameContent(draft.content())) return live;
            execute(
                    "UPDATE memory_record SET content=?,source_type=?,source_id=?,revision=revision+1,updated_at=? "
                            + "WHERE memory_id=?",
                    (statement, index) -> {
                        int next = bindContent(statement, index, draft.content(), draft.source());
                        statement.setLong(next++, Math.max(millis, row.updatedAt));
                        statement.setString(next, row.id);
                    });
            return find(live.id()).orElseThrow();
        });
    }

    @Override
    public Optional<Memory> find(MemoryId id) {
        return read(() -> selectOne(
                        "SELECT " + COLUMNS + " FROM memory_record WHERE memory_id=? AND deleted_at IS NULL",
                        (statement, index) -> statement.setString(index, id.value()))
                .map(Row::memory));
    }

    @Override
    public Optional<Memory> update(MemoryId id, long expectedRevision, String content, Instant now) {
        return write(() -> {
            int changed = execute(
                    "UPDATE memory_record SET content=?,revision=revision+1,updated_at=max(updated_at,?) "
                            + "WHERE memory_id=? AND revision=? AND deleted_at IS NULL",
                    (statement, index) -> {
                        statement.setString(index, content);
                        statement.setLong(index + 1, now.toEpochMilli());
                        statement.setString(index + 2, id.value());
                        statement.setLong(index + 3, expectedRevision);
                    });
            return changed == 1 ? find(id) : Optional.<Memory>empty();
        });
    }

    @Override
    public boolean delete(MemoryId id, long expectedRevision, Instant now) {
        return write(() -> execute(
                        "UPDATE memory_record SET content=NULL,source_type=NULL,source_id=NULL,revision=revision+1,"
                                + "updated_at=max(updated_at,?),deleted_at=max(updated_at,?) "
                                + "WHERE memory_id=? AND revision=? AND deleted_at IS NULL",
                        (statement, index) -> {
                            statement.setLong(index, now.toEpochMilli());
                            statement.setLong(index + 1, now.toEpochMilli());
                            statement.setString(index + 2, id.value());
                            statement.setLong(index + 3, expectedRevision);
                        })
                == 1);
    }

    @Override
    public Optional<MemoryScope> deletedFrom(MemoryId id, long expectedRevision) {
        return read(() -> selectOne(
                        "SELECT " + COLUMNS
                                + " FROM memory_record WHERE memory_id=? AND revision=? AND deleted_at IS NOT NULL",
                        (statement, index) -> {
                            statement.setString(index, id.value());
                            statement.setLong(index + 1, expectedRevision + 1);
                        })
                .map(Row::scope));
    }

    /**
     * The text match is case-insensitive under Java {@code Locale.ROOT} folding, which SQLite {@code lower()} (ASCII
     * only) cannot reproduce, and no SQL prefilter is safe either ({@code U+212A} folds to ASCII {@code k}). Rows are
     * therefore read newest first in bounded keyset batches and matched in Java until one row past the page is found
     * or the scope is exhausted; kind and cursor stay in SQL.
     */
    @Override
    public MemoryPage list(MemoryQuery query) {
        return read(() -> {
            Optional<MemoryCursorCodec.Position> position = query.after().map(MemoryCursorCodec::decode);
            int wanted = query.limit() + 1;
            int batch = query.text().isEmpty() ? wanted : Math.max(wanted, textScanBatch);
            List<Memory> matches = new ArrayList<>();
            while (matches.size() < wanted) {
                List<Memory> rows = listBatch(query, position, batch);
                for (Memory row : rows) {
                    if (!query.matches(row)) continue;
                    matches.add(row);
                    if (matches.size() == wanted) break;
                }
                if (rows.size() < batch) break;
                Memory last = rows.getLast();
                position = Optional.of(new MemoryCursorCodec.Position(
                        last.updatedAt(), last.id().value(), last.revision()));
            }
            boolean more = matches.size() > query.limit();
            List<Memory> page = more ? matches.subList(0, query.limit()) : matches;
            return new MemoryPage(
                    page,
                    more
                            ? Optional.of(MemoryCursorCodec.encode(
                                    page.getLast().updatedAt(),
                                    page.getLast().id().value(),
                                    page.getLast().revision()))
                            : Optional.empty());
        });
    }

    private List<Memory> listBatch(MemoryQuery query, Optional<MemoryCursorCodec.Position> after, int batch)
            throws SQLException {
        List<String> kinds = query.kinds().stream().map(Enum::name).sorted().toList();
        String sql = "SELECT " + COLUMNS + " FROM memory_record WHERE " + SCOPE_MATCH + " AND deleted_at IS NULL"
                + (kinds.isEmpty()
                        ? ""
                        : " AND kind IN (" + String.join(",", Collections.nCopies(kinds.size(), "?")) + ")")
                + (after.isEmpty() ? "" : " AND (updated_at < ? OR (updated_at = ? AND memory_id < ?))")
                + " ORDER BY updated_at DESC, memory_id DESC LIMIT ?";
        return selectMany(sql, (statement, index) -> {
            int next = bindScope(statement, index, query.scope());
            for (String kind : kinds) statement.setString(next++, kind);
            if (after.isPresent()) {
                long millis = after.orElseThrow().updatedAt().toEpochMilli();
                statement.setLong(next++, millis);
                statement.setLong(next++, millis);
                statement.setString(next++, after.orElseThrow().logicalId());
            }
            statement.setInt(next, batch);
        });
    }

    @Override
    public int clear(MemoryScope scope, Instant now) {
        return write(() -> {
            int live = selectMany(
                            "SELECT " + COLUMNS + " FROM memory_record WHERE " + SCOPE_MATCH
                                    + " AND deleted_at IS NULL",
                            (statement, index) -> bindScope(statement, index, scope))
                    .size();
            execute(
                    "DELETE FROM memory_record WHERE " + SCOPE_MATCH,
                    (statement, index) -> bindScope(statement, index, scope));
            execute(
                    "INSERT INTO memory_scope_clear(tenant_id,owner_id,owner_type,scope_type,target_id,cleared_at) "
                            + "VALUES(?,?,?,?,?,?) ON CONFLICT(tenant_id,owner_id,owner_type,scope_type,target_id) "
                            + "DO UPDATE SET cleared_at=max(cleared_at,excluded.cleared_at)",
                    (statement, index) -> {
                        int next = bindScope(statement, index, scope);
                        statement.setLong(next, now.toEpochMilli());
                    });
            return live;
        });
    }

    @Override
    public List<Memory> recent(List<MemoryScope> scopes, int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        return read(() -> {
            List<Memory> result = new ArrayList<>();
            for (MemoryScope scope : scopes) {
                result.addAll(selectMany(
                        "SELECT " + COLUMNS + " FROM memory_record WHERE " + SCOPE_MATCH
                                + " AND deleted_at IS NULL ORDER BY updated_at DESC, memory_id DESC LIMIT ?",
                        (statement, index) -> {
                            int next = bindScope(statement, index, scope);
                            statement.setInt(next, limit);
                        }));
            }
            return result.stream()
                    .sorted(Comparator.comparing(Memory::updatedAt)
                            .reversed()
                            .thenComparing(memory -> memory.id().value(), Comparator.reverseOrder()))
                    .limit(limit)
                    .toList();
        });
    }

    private void insert(MemoryId id, MemoryDraft draft, long millis) throws SQLException {
        execute(
                "INSERT INTO memory_record(" + COLUMNS + ") VALUES(?,?,?,?,?,?,?,?,?,?,?,1,?,?,NULL)",
                (statement, index) -> {
                    statement.setString(index, id.value());
                    int next = bindScope(statement, index + 1, draft.scope());
                    statement.setString(next++, draft.kind().name());
                    statement.setString(next++, draft.subjectKey());
                    next = bindContent(statement, next, draft.content(), draft.source());
                    statement.setLong(next++, millis);
                    statement.setLong(next, millis);
                });
    }

    private Optional<Instant> clearWatermark(MemoryScope scope) throws SQLException {
        try (PreparedStatement statement = unitOfWork
                .currentConnection()
                .prepareStatement("SELECT cleared_at FROM memory_scope_clear WHERE " + SCOPE_MATCH)) {
            bindScope(statement, 1, scope);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(Instant.ofEpochMilli(result.getLong(1))) : Optional.empty();
            }
        }
    }

    private Optional<Row> selectOne(String sql, Binder binder) throws SQLException {
        List<Row> rows = selectRows(sql, binder);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    private List<Memory> selectMany(String sql, Binder binder) throws SQLException {
        return selectRows(sql, binder).stream().map(Row::memory).toList();
    }

    private List<Row> selectRows(String sql, Binder binder) throws SQLException {
        try (PreparedStatement statement = unitOfWork.currentConnection().prepareStatement(sql)) {
            binder.bind(statement, 1);
            List<Row> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) rows.add(Row.from(result));
            }
            return rows;
        }
    }

    private int execute(String sql, Binder binder) throws SQLException {
        try (PreparedStatement statement = unitOfWork.currentConnection().prepareStatement(sql)) {
            binder.bind(statement, 1);
            return statement.executeUpdate();
        }
    }

    private static int bindScope(PreparedStatement statement, int index, MemoryScope scope) throws SQLException {
        statement.setString(index++, scope.tenant().tenantId());
        statement.setString(index++, scope.owner().principalId());
        statement.setString(index++, scope.owner().principalType());
        statement.setString(index++, scope.type().name());
        statement.setString(index++, scope.targetId());
        return index;
    }

    private static int bindContent(
            PreparedStatement statement, int index, String content, Optional<MemorySourceRef> source)
            throws SQLException {
        statement.setString(index++, content);
        if (source.isPresent()) {
            statement.setString(index++, source.orElseThrow().type().name());
            statement.setString(index++, source.orElseThrow().sourceId());
        } else {
            statement.setNull(index++, Types.VARCHAR);
            statement.setNull(index++, Types.VARCHAR);
        }
        return index;
    }

    private <T> T write(SqlWork<T> work) {
        return unwrap(() -> unitOfWork.execute(() -> run(work)));
    }

    private <T> T read(SqlWork<T> work) {
        return unwrap(() -> unitOfWork.executeReadOnly(() -> run(work)));
    }

    private static <T> T run(SqlWork<T> work) {
        try {
            return work.run();
        } catch (SQLException exception) {
            throw new IllegalStateException("SQLite Memory operation failed", exception);
        }
    }

    private static <T> T unwrap(Supplier<T> work) {
        try {
            return work.get();
        } catch (SqliteStoreException exception) {
            if (exception.getCause() instanceof MemoryOperationException memoryFailure) throw memoryFailure;
            throw exception;
        }
    }

    private static MemoryOperationException stale() {
        return new MemoryOperationException("MEMORY_WRITE_STALE");
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement, int index) throws SQLException;
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run() throws SQLException;
    }

    private record Row(
            String id,
            MemoryScope scope,
            MemoryKind kind,
            String subjectKey,
            String content,
            Optional<MemorySourceRef> source,
            long revision,
            long createdAt,
            long updatedAt,
            Long deletedAt) {
        static Row from(ResultSet result) throws SQLException {
            String sourceType = result.getString("source_type");
            Object deleted = result.getObject("deleted_at");
            return new Row(
                    result.getString("memory_id"),
                    new MemoryScope(
                            new TenantRef(result.getString("tenant_id")),
                            new PrincipalRef(result.getString("owner_id"), result.getString("owner_type")),
                            MemoryScopeType.valueOf(result.getString("scope_type")),
                            result.getString("target_id")),
                    MemoryKind.valueOf(result.getString("kind")),
                    result.getString("subject_key"),
                    result.getString("content"),
                    sourceType == null
                            ? Optional.empty()
                            : Optional.of(new MemorySourceRef(
                                    MemorySourceType.valueOf(sourceType), result.getString("source_id"))),
                    result.getLong("revision"),
                    result.getLong("created_at"),
                    result.getLong("updated_at"),
                    deleted == null ? null : ((Number) deleted).longValue());
        }

        Memory memory() {
            return new Memory(
                    new MemoryId(id),
                    revision,
                    scope,
                    kind,
                    subjectKey,
                    content,
                    source,
                    Instant.ofEpochMilli(createdAt),
                    Instant.ofEpochMilli(updatedAt));
        }
    }
}

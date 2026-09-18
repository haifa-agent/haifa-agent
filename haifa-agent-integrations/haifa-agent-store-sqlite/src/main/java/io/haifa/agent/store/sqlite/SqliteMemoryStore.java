package io.haifa.agent.store.sqlite;

import io.haifa.agent.memory.api.Memory;
import io.haifa.agent.memory.api.MemoryActor;
import io.haifa.agent.memory.api.MemoryAuditEvent;
import io.haifa.agent.memory.api.MemoryAuditStore;
import io.haifa.agent.memory.api.MemoryCandidate;
import io.haifa.agent.memory.api.MemoryCandidateId;
import io.haifa.agent.memory.api.MemoryCandidatePage;
import io.haifa.agent.memory.api.MemoryCandidateQuery;
import io.haifa.agent.memory.api.MemoryCandidateRepository;
import io.haifa.agent.memory.api.MemoryConflict;
import io.haifa.agent.memory.api.MemoryCursorCodec;
import io.haifa.agent.memory.api.MemoryId;
import io.haifa.agent.memory.api.MemoryKind;
import io.haifa.agent.memory.api.MemoryOperationException;
import io.haifa.agent.memory.api.MemoryPage;
import io.haifa.agent.memory.api.MemoryQuery;
import io.haifa.agent.memory.api.MemoryRecordQuery;
import io.haifa.agent.memory.api.MemoryRef;
import io.haifa.agent.memory.api.MemoryRepository;
import io.haifa.agent.memory.api.MemoryScope;
import io.haifa.agent.memory.api.MemorySecurityLabel;
import io.haifa.agent.memory.api.MemoryStatus;
import io.haifa.agent.memory.api.MemoryTombstone;
import io.haifa.agent.memory.api.MemoryVersion;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** Production SQLite Memory repository. Deferred lifecycle/administration APIs fail closed. */
public final class SqliteMemoryStore implements MemoryCandidateRepository, MemoryRepository, MemoryAuditStore {
    private static final int SCHEMA_VERSION = 1;
    private final SqliteRuntimeUnitOfWork unitOfWork;
    private final int maximumPayloadBytes;
    private final SqliteMemoryPayloadCodec codec = new SqliteMemoryPayloadCodec();

    public SqliteMemoryStore(SqliteRuntimeUnitOfWork unitOfWork) {
        this(unitOfWork, SqliteStoreConfiguration.DEFAULT_MAXIMUM_PAYLOAD_BYTES);
    }

    public SqliteMemoryStore(SqliteRuntimeUnitOfWork unitOfWork, int maximumPayloadBytes) {
        this.unitOfWork = java.util.Objects.requireNonNull(unitOfWork);
        if (maximumPayloadBytes < 1) throw new IllegalArgumentException("maximumPayloadBytes must be positive");
        this.maximumPayloadBytes = maximumPayloadBytes;
    }

    @Override
    public MemoryCandidate save(MemoryCandidate candidate) {
        return execute(() -> {
            byte[] payload = codec.encodeCandidate(candidate);
            requireBounded(payload);
            String sql =
                    """
                    INSERT INTO memory_candidate(candidate_id,request_key_digest,tenant_id,owner_id,scope_type,
                    target_id,visibility,security_label_bits,kind,subject_key,normalized_digest,status,revision,updated_at,
                    payload_schema_version,payload_type,payload_hash,payload)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT(candidate_id) DO UPDATE SET request_key_digest=excluded.request_key_digest,
                    security_label_bits=excluded.security_label_bits,kind=excluded.kind,subject_key=excluded.subject_key,
                    normalized_digest=excluded.normalized_digest,status=excluded.status,
                    revision=excluded.revision,updated_at=excluded.updated_at,
                    payload_hash=excluded.payload_hash,payload=excluded.payload
                    WHERE memory_candidate.revision = excluded.revision - 1
                       OR (memory_candidate.revision = excluded.revision
                           AND memory_candidate.payload_hash = excluded.payload_hash)
                    """;
            try (PreparedStatement s = unitOfWork.currentConnection().prepareStatement(sql)) {
                int i = 1;
                s.setString(i++, candidate.id().value());
                s.setString(i++, candidate.requestKey());
                i = bindScope(s, i, candidate.scope(), candidate.securityLabels());
                s.setString(i++, candidate.kind().name());
                s.setString(i++, candidate.subjectKey());
                s.setString(i++, candidate.normalizedDigest());
                s.setString(i++, candidate.status().name());
                s.setLong(i++, candidate.revision());
                s.setLong(i++, candidate.updatedAt().toEpochMilli());
                s.setInt(i++, SCHEMA_VERSION);
                s.setString(i++, "memory-candidate");
                s.setString(i++, hash(payload));
                s.setBytes(i, payload);
                if (s.executeUpdate() != 1) throw new MemoryOperationException("MEMORY_CANDIDATE_REVISION_CONFLICT");
                return candidate;
            } catch (SQLException exception) {
                throw failure(exception);
            }
        });
    }

    @Override
    public Optional<MemoryCandidate> find(MemoryCandidateId id) {
        return execute(() ->
                selectCandidate("SELECT candidate_id,payload FROM memory_candidate WHERE candidate_id=?", id.value()));
    }

    @Override
    public Optional<MemoryCandidate> findAuthorized(MemoryCandidateId id, MemoryActor actor) {
        return execute(() -> {
            String sql =
                    "SELECT candidate_id,payload FROM memory_candidate WHERE candidate_id=? AND tenant_id=? AND owner_id=?";
            try (PreparedStatement s = unitOfWork.currentConnection().prepareStatement(sql)) {
                s.setString(1, id.value());
                s.setString(2, actor.tenant().tenantId());
                s.setString(3, actor.principal().principalId());
                return oneCandidate(s);
            } catch (SQLException e) {
                throw failure(e);
            }
        });
    }

    @Override
    public Optional<MemoryCandidate> findByRequestKey(MemoryScope scope, String requestKey) {
        return execute(() -> selectCandidateByScope(
                scope, "request_key_digest=?", (statement, index) -> statement.setString(index, requestKey)));
    }

    @Override
    public Optional<MemoryCandidate> findEquivalentPending(
            MemoryScope scope, MemoryKind kind, String normalizedDigest) {
        return execute(() -> selectCandidateByScope(
                scope, "status='PENDING' AND kind=? AND normalized_digest=?", (statement, index) -> {
                    statement.setString(index, kind.name());
                    statement.setString(index + 1, normalizedDigest);
                }));
    }

    @Override
    public List<MemoryCandidate> allCandidates() {
        throw deferred();
    }

    @Override
    public MemoryCandidatePage query(MemoryCandidateQuery query) {
        return execute(() -> {
            var cursor = query.after().map(MemoryCursorCodec::decode);
            List<String> statuses = enumNames(query.statuses());
            List<String> kinds = enumNames(query.kinds());
            String sql =
                    """
                    SELECT candidate_id,payload FROM memory_candidate
                    WHERE tenant_id=? AND owner_id=? AND scope_type=? AND target_id=? AND visibility=?
                    """
                            + inClause("status", statuses.size())
                            + inClause("kind", kinds.size())
                            + """
                    AND (? IS NULL OR updated_at < ?)
                    AND (? IS NULL OR updated_at < ? OR (updated_at=? AND candidate_id < ?))
                    ORDER BY updated_at DESC,candidate_id DESC LIMIT ?
                    """;
            List<MemoryCandidate> values = new ArrayList<>();
            try (PreparedStatement s = unitOfWork.currentConnection().prepareStatement(sql)) {
                int i = bindScopeIdentity(s, 1, query.scope());
                i = bindStrings(s, i, statuses);
                i = bindStrings(s, i, kinds);
                Long before = query.updatedBefore().map(Instant::toEpochMilli).orElse(null);
                setNullableLong(s, i++, before);
                setNullableLong(s, i++, before);
                Long after = cursor.map(p -> p.updatedAt().toEpochMilli()).orElse(null);
                setNullableLong(s, i++, after);
                setNullableLong(s, i++, after);
                setNullableLong(s, i++, after);
                s.setString(
                        i++, cursor.map(MemoryCursorCodec.Position::logicalId).orElse(null));
                s.setInt(i, query.limit() + 1);
                try (ResultSet rs = s.executeQuery()) {
                    while (rs.next()) values.add(codec.decodeCandidate(rs.getString(1), rs.getBytes(2)));
                }
            } catch (SQLException e) {
                throw failure(e);
            }
            return candidatePage(values, query.limit());
        });
    }

    @Override
    public void purgeScope(MemoryScope scope) {
        throw deferred();
    }

    @Override
    public Memory save(Memory memory) {
        if (memory.status() != MemoryStatus.ACTIVE && memory.status() != MemoryStatus.INVALIDATED) throw deferred();
        return execute(() -> {
            byte[] payload = codec.encodeMemory(memory);
            requireBounded(payload);
            String sql =
                    """
                    INSERT INTO memory_record(memory_id,memory_version,tenant_id,owner_id,scope_type,target_id,
                    visibility,security_label_bits,kind,subject_key,status,normalized_digest,updated_at,
                    payload_schema_version,payload_type,payload_hash,payload)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT(memory_id,memory_version) DO UPDATE SET status=excluded.status,
                    security_label_bits=excluded.security_label_bits,updated_at=excluded.updated_at,
                    payload_hash=excluded.payload_hash,payload=excluded.payload
                    """;
            try (PreparedStatement s = unitOfWork.currentConnection().prepareStatement(sql)) {
                int i = 1;
                s.setString(i++, memory.id().value());
                s.setLong(i++, memory.version().value());
                i = bindScope(s, i, memory.scope(), memory.securityLabels());
                s.setString(i++, memory.kind().name());
                s.setString(i++, memory.subjectKey());
                s.setString(i++, memory.status().name());
                s.setString(i++, memory.normalizedDigest());
                s.setLong(i++, memory.updatedAt().toEpochMilli());
                s.setInt(i++, SCHEMA_VERSION);
                s.setString(i++, "memory-record");
                s.setString(i++, hash(payload));
                s.setBytes(i, payload);
                s.executeUpdate();
                return memory;
            } catch (SQLException e) {
                throw failure(e);
            }
        });
    }

    @Override
    public Optional<Memory> find(MemoryId id, MemoryVersion version) {
        return execute(() -> selectMemory(
                "SELECT memory_id,memory_version,payload FROM memory_record WHERE memory_id=? AND memory_version=?",
                (statement, index) -> {
                    statement.setString(index, id.value());
                    statement.setLong(index + 1, version.value());
                }));
    }

    @Override
    public Optional<Memory> findAuthorized(MemoryId id, MemoryVersion version, MemoryActor actor) {
        return execute(() -> selectMemory(
                "SELECT memory_id,memory_version,payload FROM memory_record WHERE memory_id=? AND memory_version=? AND tenant_id=? AND owner_id=?",
                (statement, index) -> {
                    statement.setString(index, id.value());
                    statement.setLong(index + 1, version.value());
                    statement.setString(index + 2, actor.tenant().tenantId());
                    statement.setString(index + 3, actor.principal().principalId());
                }));
    }

    @Override
    public Optional<Memory> latest(MemoryId id) {
        return execute(() -> selectMemory(
                "SELECT memory_id,memory_version,payload FROM memory_record WHERE memory_id=? ORDER BY memory_version DESC LIMIT 1",
                (statement, index) -> statement.setString(index, id.value())));
    }

    @Override
    public Optional<Memory> findActiveEquivalent(MemoryScope scope, MemoryKind kind, String digest) {
        return execute(() ->
                selectMemoryByScope(scope, "status='ACTIVE' AND kind=? AND normalized_digest=?", (statement, index) -> {
                    statement.setString(index, kind.name());
                    statement.setString(index + 1, digest);
                }));
    }

    @Override
    public Optional<Memory> findActiveBySubject(MemoryScope scope, MemoryKind kind, String subjectKey) {
        return execute(
                () -> selectMemoryByScope(scope, "status='ACTIVE' AND kind=? AND subject_key=?", (statement, index) -> {
                    statement.setString(index, kind.name());
                    statement.setString(index + 1, subjectKey);
                }));
    }

    @Override
    public List<Memory> allMemories() {
        throw deferred();
    }

    @Override
    public List<Memory> searchAuthorizedActive(MemoryQuery query, int fetchLimit) {
        return execute(() -> {
            List<Memory> result = new ArrayList<>();
            int allowedBits = bits(query.allowedSecurityLabels());
            for (MemoryScope scope : query.scopes()) {
                String sql =
                        """
                        SELECT memory_id,memory_version,payload FROM memory_record
                        WHERE tenant_id=? AND owner_id=? AND scope_type=? AND target_id=? AND visibility=?
                        AND status='ACTIVE' AND (security_label_bits & ~?) = 0
                        ORDER BY updated_at DESC,memory_id DESC,memory_version DESC LIMIT ?
                        """;
                try (PreparedStatement s = unitOfWork.currentConnection().prepareStatement(sql)) {
                    int i = bindScopeIdentity(s, 1, scope);
                    s.setInt(i++, allowedBits);
                    s.setInt(i, fetchLimit);
                    try (ResultSet rs = s.executeQuery()) {
                        while (rs.next()) {
                            Memory value = codec.decodeMemory(rs.getString(1), rs.getLong(2), rs.getBytes(3));
                            if ((query.kinds().isEmpty() || query.kinds().contains(value.kind()))
                                    && (query.queryText().isBlank()
                                            || value.content()
                                                    .orElseThrow()
                                                    .boundedText()
                                                    .toLowerCase(java.util.Locale.ROOT)
                                                    .contains(query.queryText().toLowerCase(java.util.Locale.ROOT)))) {
                                result.add(value);
                            }
                        }
                    }
                } catch (SQLException e) {
                    throw failure(e);
                }
            }
            return result.stream()
                    .sorted(Comparator.comparing(Memory::updatedAt)
                            .reversed()
                            .thenComparing(value -> value.id().value(), Comparator.reverseOrder()))
                    .limit(fetchLimit)
                    .toList();
        });
    }

    @Override
    public MemoryPage query(MemoryRecordQuery query) {
        return execute(() -> {
            var cursor = query.after().map(MemoryCursorCodec::decode);
            List<String> statuses = enumNames(query.statuses());
            List<String> kinds = enumNames(query.kinds());
            String sql =
                    """
                    SELECT memory_id,memory_version,payload FROM memory_record
                    WHERE tenant_id=? AND owner_id=? AND scope_type=? AND target_id=? AND visibility=?
                    """
                            + inClause("status", statuses.size())
                            + inClause("kind", kinds.size())
                            + """
                    AND (? IS NULL OR updated_at < ?)
                    AND (? IS NULL OR updated_at < ? OR (updated_at=? AND
                        (memory_id < ? OR (memory_id=? AND memory_version < ?))))
                    ORDER BY updated_at DESC,memory_id DESC,memory_version DESC LIMIT ?
                    """;
            List<Memory> values = new ArrayList<>();
            try (PreparedStatement s = unitOfWork.currentConnection().prepareStatement(sql)) {
                int i = bindScopeIdentity(s, 1, query.scope());
                i = bindStrings(s, i, statuses);
                i = bindStrings(s, i, kinds);
                Long before = query.updatedBefore().map(Instant::toEpochMilli).orElse(null);
                setNullableLong(s, i++, before);
                setNullableLong(s, i++, before);
                Long after = cursor.map(p -> p.updatedAt().toEpochMilli()).orElse(null);
                String logicalId =
                        cursor.map(MemoryCursorCodec.Position::logicalId).orElse(null);
                setNullableLong(s, i++, after);
                setNullableLong(s, i++, after);
                setNullableLong(s, i++, after);
                s.setString(i++, logicalId);
                s.setString(i++, logicalId);
                if (cursor.isPresent()) s.setLong(i++, cursor.orElseThrow().sequence());
                else s.setNull(i++, java.sql.Types.BIGINT);
                s.setInt(i, query.limit() + 1);
                try (ResultSet rs = s.executeQuery()) {
                    while (rs.next()) values.add(codec.decodeMemory(rs.getString(1), rs.getLong(2), rs.getBytes(3)));
                }
            } catch (SQLException e) {
                throw failure(e);
            }
            return memoryPage(values, query.limit());
        });
    }

    @Override
    public MemoryConflict saveConflict(MemoryConflict conflict) {
        throw deferred();
    }

    @Override
    public Optional<MemoryConflict> conflictFor(MemoryCandidateId candidateId) {
        throw deferred();
    }

    @Override
    public List<MemoryConflict> conflicts() {
        throw deferred();
    }

    @Override
    public void saveTombstone(MemoryTombstone tombstone) {
        throw deferred();
    }

    @Override
    public List<MemoryTombstone> tombstones() {
        throw deferred();
    }

    @Override
    public void record(MemoryAuditEvent event) {
        execute(() -> {
            String sql =
                    """
                    INSERT OR IGNORE INTO memory_audit_event(operation,candidate_id,memory_id,memory_version,
                    tenant_id,owner_id,scope_type,target_id,visibility,actor_id,safe_attributes_json,
                    occurred_at,idempotency_key_digest,request_digest,candidate_revision)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """;
            try (PreparedStatement s = unitOfWork.currentConnection().prepareStatement(sql)) {
                int i = 1;
                s.setString(i++, event.operation());
                s.setString(
                        i++, event.candidateId().map(MemoryCandidateId::value).orElse(null));
                s.setString(i++, event.memory().map(ref -> ref.id().value()).orElse(null));
                if (event.memory().isPresent())
                    s.setLong(i++, event.memory().orElseThrow().version().value());
                else s.setNull(i++, java.sql.Types.BIGINT);
                i = bindScopeIdentity(s, i, event.scope());
                s.setString(i++, event.actorId());
                s.setString(i++, codec.writeSafeAttributes(event.safeAttributes()));
                s.setLong(i++, event.occurredAt().toEpochMilli());
                s.setString(i++, event.idempotencyKeyDigest().orElse(null));
                s.setString(i++, event.requestDigest().orElse(null));
                if (event.candidateRevision().isPresent())
                    s.setLong(i, event.candidateRevision().orElseThrow());
                else s.setNull(i, java.sql.Types.BIGINT);
                s.executeUpdate();
                return null;
            } catch (SQLException e) {
                throw failure(e);
            }
        });
    }

    @Override
    public Optional<MemoryAuditEvent> findByIdempotency(
            MemoryScope scope, String operation, String idempotencyKeyDigest) {
        return execute(() -> {
            String sql =
                    """
                    SELECT candidate_id,memory_id,memory_version,actor_id,safe_attributes_json,occurred_at,
                    request_digest,candidate_revision FROM memory_audit_event
                    WHERE tenant_id=? AND owner_id=? AND scope_type=? AND target_id=? AND visibility=?
                    AND operation=? AND idempotency_key_digest=?
                    """;
            try (PreparedStatement s = unitOfWork.currentConnection().prepareStatement(sql)) {
                int i = bindScopeIdentity(s, 1, scope);
                s.setString(i++, operation);
                s.setString(i, idempotencyKeyDigest);
                try (ResultSet rs = s.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    Optional<MemoryRef> ref = Optional.ofNullable(rs.getString(2))
                            .map(id -> new MemoryRef(new MemoryId(id), new MemoryVersion(rsLong(rs, 3))));
                    Optional<Long> revision = rs.getObject(8) == null ? Optional.empty() : Optional.of(rs.getLong(8));
                    return Optional.of(new MemoryAuditEvent(
                            operation,
                            Optional.ofNullable(rs.getString(1)).map(MemoryCandidateId::new),
                            ref,
                            scope,
                            rs.getString(4),
                            java.util.Map.of("replayed", "true"),
                            Instant.ofEpochMilli(rs.getLong(6)),
                            Optional.of(idempotencyKeyDigest),
                            Optional.of(rs.getString(7)),
                            revision));
                }
            } catch (SQLException e) {
                throw failure(e);
            }
        });
    }

    public void invalidateSelections() {
        unitOfWork.execute(() -> {
            try (Statement statement = unitOfWork.currentConnection().createStatement()) {
                statement.executeUpdate("DELETE FROM memory_selection");
                return null;
            } catch (SQLException e) {
                throw failure(e);
            }
        });
    }

    private Optional<MemoryCandidate> selectCandidate(String sql, String id) {
        try (PreparedStatement s = unitOfWork.currentConnection().prepareStatement(sql)) {
            s.setString(1, id);
            return oneCandidate(s);
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    private Optional<MemoryCandidate> selectCandidateByScope(MemoryScope scope, String suffix, SqlBinder binder) {
        String sql =
                "SELECT candidate_id,payload FROM memory_candidate WHERE tenant_id=? AND owner_id=? AND scope_type=? AND target_id=? AND visibility=? AND "
                        + suffix + " LIMIT 1";
        try (PreparedStatement s = unitOfWork.currentConnection().prepareStatement(sql)) {
            int offset = bindScopeIdentity(s, 1, scope);
            binder.bind(s, offset);
            return oneCandidate(s);
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    private Optional<MemoryCandidate> oneCandidate(PreparedStatement statement) throws SQLException {
        try (ResultSet rs = statement.executeQuery()) {
            return rs.next() ? Optional.of(codec.decodeCandidate(rs.getString(1), rs.getBytes(2))) : Optional.empty();
        }
    }

    private Optional<Memory> selectMemory(String sql, SqlBinder binder) {
        try (PreparedStatement s = unitOfWork.currentConnection().prepareStatement(sql)) {
            binder.bind(s, 1);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next()
                        ? Optional.of(codec.decodeMemory(rs.getString(1), rs.getLong(2), rs.getBytes(3)))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    private Optional<Memory> selectMemoryByScope(MemoryScope scope, String suffix, SqlBinder binder) {
        String sql =
                "SELECT memory_id,memory_version,payload FROM memory_record WHERE tenant_id=? AND owner_id=? AND scope_type=? AND target_id=? AND visibility=? AND "
                        + suffix + " LIMIT 1";
        try (PreparedStatement s = unitOfWork.currentConnection().prepareStatement(sql)) {
            int offset = bindScopeIdentity(s, 1, scope);
            binder.bind(s, offset);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next()
                        ? Optional.of(codec.decodeMemory(rs.getString(1), rs.getLong(2), rs.getBytes(3)))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    private <T> T execute(Supplier<T> work) {
        return unitOfWork.isActive() ? work.get() : unitOfWork.execute(work);
    }

    private static int bindScope(PreparedStatement s, int index, MemoryScope scope, Set<MemorySecurityLabel> labels)
            throws SQLException {
        int next = bindScopeIdentity(s, index, scope);
        s.setInt(next++, bits(labels));
        return next;
    }

    private static int bindScopeIdentity(PreparedStatement s, int index, MemoryScope scope) throws SQLException {
        s.setString(index++, scope.tenant().tenantId());
        s.setString(index++, scope.owner().principalId());
        s.setString(index++, scope.type().name());
        s.setString(index++, scope.targetId());
        s.setString(index++, scope.visibility().name());
        return index;
    }

    private static int bits(Set<MemorySecurityLabel> labels) {
        int bits = 0;
        for (MemorySecurityLabel label : labels) bits |= 1 << label.ordinal();
        return bits;
    }

    private static void setNullableLong(PreparedStatement s, int index, Long value) throws SQLException {
        if (value == null) s.setNull(index, java.sql.Types.BIGINT);
        else s.setLong(index, value);
    }

    private static List<String> enumNames(Set<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).sorted().toList();
    }

    private static String inClause(String column, int size) {
        return size == 0
                ? ""
                : " AND " + column + " IN (" + String.join(",", java.util.Collections.nCopies(size, "?")) + ")\n";
    }

    private static int bindStrings(PreparedStatement statement, int index, List<String> values) throws SQLException {
        for (String value : values) statement.setString(index++, value);
        return index;
    }

    private static MemoryCandidatePage candidatePage(List<MemoryCandidate> values, int limit) {
        boolean more = values.size() > limit;
        List<MemoryCandidate> page = values.stream().limit(limit).toList();
        return new MemoryCandidatePage(
                page,
                more && !page.isEmpty()
                        ? Optional.of(MemoryCursorCodec.encode(
                                page.get(page.size() - 1).updatedAt(),
                                page.get(page.size() - 1).id().value(),
                                page.get(page.size() - 1).revision()))
                        : Optional.empty());
    }

    private static MemoryPage memoryPage(List<Memory> values, int limit) {
        boolean more = values.size() > limit;
        List<Memory> page = values.stream().limit(limit).toList();
        return new MemoryPage(
                page,
                more && !page.isEmpty()
                        ? Optional.of(MemoryCursorCodec.encode(
                                page.get(page.size() - 1).updatedAt(),
                                page.get(page.size() - 1).id().value(),
                                page.get(page.size() - 1).version().value()))
                        : Optional.empty());
    }

    private static String hash(byte[] bytes) {
        try {
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static long rsLong(ResultSet rs, int index) {
        try {
            return rs.getLong(index);
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    private static RuntimeException failure(SQLException exception) {
        return new IllegalStateException("SQLite Memory operation failed", exception);
    }

    private static MemoryOperationException deferred() {
        return new MemoryOperationException("MEMORY_DEFERRED_OPERATION");
    }

    private void requireBounded(byte[] payload) {
        if (payload.length > maximumPayloadBytes) {
            throw new MemoryOperationException("MEMORY_PAYLOAD_TOO_LARGE");
        }
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement statement, int startIndex) throws SQLException;
    }
}

package io.haifa.agent.store.sqlite;

import io.haifa.agent.memory.api.MemoryEvidenceRef;
import io.haifa.agent.memory.api.MemoryEvidenceVerifier;
import io.haifa.agent.memory.api.MemoryScope;
import io.haifa.agent.memory.api.MemoryScopeType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Verifies evidence against authoritative SQLite Runtime/Conversation rows, never caller text. */
public final class SqliteMemoryEvidenceVerifier implements MemoryEvidenceVerifier {
    private final SqliteRuntimeUnitOfWork unitOfWork;

    public SqliteMemoryEvidenceVerifier(SqliteRuntimeUnitOfWork unitOfWork) {
        this.unitOfWork = java.util.Objects.requireNonNull(unitOfWork);
    }

    @Override
    public boolean verify(MemoryScope scope, MemoryEvidenceRef evidence) {
        return unitOfWork.execute(() -> switch (evidence.source().type()) {
            case MESSAGE -> existsMessage(scope, evidence.source().sourceId(), evidence.contentDigest());
            case INTERACTION_RESPONSE ->
                existsRunBound(
                        scope,
                        "SELECT r.run_id FROM interaction_response ir JOIN interaction_request iq ON iq.request_id=ir.request_id "
                                + "JOIN run r ON r.run_id=iq.run_id WHERE ir.response_id=? AND ir.inputs_hash=?",
                        evidence.source().sourceId(),
                        evidence.contentDigest());
            case TOOL_CALL ->
                existsRunBound(
                        scope,
                        "SELECT r.run_id FROM tool_call t JOIN run r ON r.run_id=t.run_id "
                                + "WHERE t.tool_call_id=? AND t.arguments_hash=?",
                        evidence.source().sourceId(),
                        evidence.contentDigest());
            case DERIVED_ASSET ->
                existsRunBound(
                        scope,
                        "SELECT r.run_id FROM tool_result_asset a JOIN tool_call t ON t.tool_call_id=a.tool_call_id "
                                + "JOIN run r ON r.run_id=t.run_id WHERE a.asset_ref=? AND a.result_hash=?",
                        evidence.source().sourceId(),
                        evidence.contentDigest());
            case EXPLICIT_USER_COMMAND -> false;
        });
    }

    private boolean existsMessage(MemoryScope scope, String id, String digest) {
        String sql =
                """
                SELECT m.session_id,r.run_id FROM session_message m
                JOIN sdk_conversation c ON c.session_id=m.session_id
                LEFT JOIN run r ON r.run_id=m.run_id
                WHERE m.message_id=? AND m.content_hash=? AND c.tenant_id=? AND c.principal_id=? AND c.principal_type=?
                """;
        try (PreparedStatement s = unitOfWork.currentConnection().prepareStatement(sql)) {
            s.setString(1, id);
            s.setString(2, digest);
            s.setString(3, scope.tenant().tenantId());
            s.setString(4, scope.owner().principalId());
            s.setString(5, scope.owner().principalType());
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return false;
                return targetMatches(scope, rs.getString(1), rs.getString(2));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to verify Memory evidence", exception);
        }
    }

    private boolean existsRunBound(MemoryScope scope, String sql, String id, String digest) {
        try (PreparedStatement s = unitOfWork
                .currentConnection()
                .prepareStatement(sql + " AND r.tenant_id=? AND r.principal_id=? AND r.principal_type=?")) {
            s.setString(1, id);
            s.setString(2, digest);
            s.setString(3, scope.tenant().tenantId());
            s.setString(4, scope.owner().principalId());
            s.setString(5, scope.owner().principalType());
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return false;
                String runId = rs.getString(1);
                return scope.type() == MemoryScopeType.USER || scope.targetId().equals(runId);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to verify Memory evidence", exception);
        }
    }

    private static boolean targetMatches(MemoryScope scope, String sessionId, String runId) {
        return switch (scope.type()) {
            case USER -> true;
            case SESSION -> scope.targetId().equals(sessionId);
            case RUN -> scope.targetId().equals(runId);
        };
    }
}

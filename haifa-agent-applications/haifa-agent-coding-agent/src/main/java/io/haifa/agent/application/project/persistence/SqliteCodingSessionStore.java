package io.haifa.agent.application.project.persistence;

import io.haifa.agent.application.project.product.coding.CodingCommandBinding;
import io.haifa.agent.application.project.product.coding.CodingDispatchClaim;
import io.haifa.agent.application.project.product.coding.CodingFollowUp;
import io.haifa.agent.application.project.product.coding.CodingFollowUpStatus;
import io.haifa.agent.application.project.product.coding.CodingSessionActivity;
import io.haifa.agent.application.project.product.coding.CodingSessionQuery;
import io.haifa.agent.application.project.product.coding.CodingSessionStore;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationProtector;
import io.haifa.agent.store.sqlite.SqliteRuntimeUnitOfWork;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** MyBatis product store sharing the Runtime SQLite unit of work and BEGIN IMMEDIATE boundary. */
public final class SqliteCodingSessionStore implements CodingSessionStore {
    private static final String SCHEMA_VERSION = CodingContentPayloadCodec.SCHEMA_VERSION;

    private final SqliteRuntimeUnitOfWork unitOfWork;
    private final CodingContentPayloadCodec content;

    public SqliteCodingSessionStore(
            SqliteRuntimeUnitOfWork unitOfWork, ModelContinuationProtector protector, int maximumPayloadBytes) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        this.content = new CodingContentPayloadCodec(protector, maximumPayloadBytes);
    }

    @Override
    public CodingCommandBinding reserveCommand(CodingCommandBinding candidate) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        return unitOfWork.execute(() -> {
            CodingSessionMapper mapper = mapper();
            CodingSessionCommandRow existing = mapper.findCommand(
                    candidate.callerScopeDigest(), candidate.operation(), candidate.idempotencyKeyDigest());
            if (existing != null) {
                CodingCommandBinding value = command(existing);
                if (!value.requestDigest().equals(candidate.requestDigest())
                        || !value.projectId().equals(candidate.projectId())) {
                    throw conflict("idempotency key is bound to another request");
                }
                return value;
            }
            CodingContentPayloadCodec.ProtectedContent payload =
                    content.encode(candidate.message(), candidate.attachments(), commandBinding(candidate));
            requireOne(
                    mapper.insertCommand(new CodingSessionCommandRow(
                            candidate.callerScopeDigest(),
                            candidate.operation(),
                            candidate.idempotencyKeyDigest(),
                            SCHEMA_VERSION,
                            candidate.requestDigest(),
                            candidate.dispatchKey(),
                            candidate.sessionId().value(),
                            candidate.projectId().value(),
                            payload.nonce(),
                            payload.ciphertext(),
                            payload.digest(),
                            candidate.runId().map(AgentRunId::value).orElse(null),
                            candidate.createdAt())),
                    "coding command insert");
            return candidate;
        });
    }

    @Override
    public CodingCommandBinding completeCommand(String dispatchKey, AgentRunId runId) {
        return unitOfWork.execute(() -> {
            CodingSessionMapper mapper = mapper();
            CodingSessionCommandRow row = requireCommand(mapper.findCommandByDispatchKey(dispatchKey));
            CodingCommandBinding current = command(row);
            if (current.runId().isPresent()) {
                if (!current.runId().orElseThrow().equals(runId)) {
                    throw conflict("coding command resolved to another Run");
                }
                return current;
            }
            requireOne(mapper.completeCommand(dispatchKey, runId.value()), "coding command completion");
            return command(requireCommand(mapper.findCommandByDispatchKey(dispatchKey)));
        });
    }

    @Override
    public Optional<CodingCommandBinding> findCommandByDispatchKey(String dispatchKey) {
        return unitOfWork.execute(() -> Optional.ofNullable(mapper().findCommandByDispatchKey(dispatchKey))
                .map(this::command));
    }

    @Override
    public CodingSessionActivity createActivity(CodingSessionActivity activity) {
        Objects.requireNonNull(activity, "activity must not be null");
        return unitOfWork.execute(() -> {
            CodingSessionMapper mapper = mapper();
            CodingSessionActivityRow existing =
                    mapper.findActivity(activity.sessionId().value());
            if (existing != null) {
                CodingSessionActivity value = activity(existing);
                if (!value.equals(activity)) throw conflict("coding session activity already exists");
                return value;
            }
            requireOne(mapper.insertActivity(activityRow(activity)), "coding activity insert");
            return activity;
        });
    }

    @Override
    public Optional<CodingSessionActivity> findActivity(AgentSessionId sessionId) {
        return unitOfWork.execute(() ->
                Optional.ofNullable(mapper().findActivity(sessionId.value())).map(this::activity));
    }

    @Override
    public List<CodingSessionActivity> listActivities(
            TenantRef tenant, PrincipalRef principal, ProjectId projectId, CodingSessionQuery query) {
        return unitOfWork.execute(() -> mapper()
                .listActivities(
                        tenant.tenantId(),
                        principal.principalId(),
                        principal.principalType(),
                        projectId.value(),
                        query.text().map(SqliteCodingSessionStore::like).orElse(null),
                        query.after().map(value -> value.lastActivityAt()).orElse(null),
                        query.after().map(value -> value.sessionId().value()).orElse(null),
                        query.limit() + 1)
                .stream()
                .map(this::activity)
                .toList());
    }

    @Override
    public CodingSessionActivity reserveActive(
            AgentSessionId sessionId, long expectedRevision, String dispatchKey, Instant updatedAt) {
        return unitOfWork.execute(() -> {
            CodingSessionMapper mapper = mapper();
            CodingSessionActivity current = requireActivity(mapper, sessionId);
            if (current.activeDispatchKey().filter(dispatchKey::equals).isPresent()) return current;
            if (current.revision() != expectedRevision) throw conflict("coding session revision is stale");
            if (current.activeRunId().isPresent() || current.activeDispatchKey().isPresent()) {
                throw conflict("coding session already has an active Run or dispatch");
            }
            requireOne(
                    mapper.reserveActive(sessionId.value(), expectedRevision, dispatchKey, updatedAt),
                    "active dispatch reservation");
            return requireActivity(mapper, sessionId);
        });
    }

    @Override
    public CodingSessionActivity activateRun(
            AgentSessionId sessionId, String dispatchKey, AgentRunId runId, long runVersion, Instant updatedAt) {
        return unitOfWork.execute(() -> {
            CodingSessionMapper mapper = mapper();
            CodingSessionActivity current = requireActivity(mapper, sessionId);
            if (current.activeRunId().filter(runId::equals).isPresent()) return current;
            if (current.activeDispatchKey().filter(dispatchKey::equals).isEmpty()) {
                throw conflict("active dispatch changed before Run activation");
            }
            requireOne(
                    mapper.activateRun(sessionId.value(), dispatchKey, runId.value(), runVersion, updatedAt),
                    "Run activation");
            return requireActivity(mapper, sessionId);
        });
    }

    @Override
    public CodingSessionActivity clearActive(
            AgentSessionId sessionId, AgentRunId runId, long expectedRevision, Instant updatedAt) {
        return unitOfWork.execute(() -> {
            CodingSessionMapper mapper = mapper();
            CodingSessionActivity current = requireActivity(mapper, sessionId);
            if (current.activeRunId().isEmpty()) return current;
            requireOne(
                    mapper.clearActive(sessionId.value(), runId.value(), expectedRevision, updatedAt),
                    "active Run reconciliation");
            return requireActivity(mapper, sessionId);
        });
    }

    @Override
    public CodingFollowUp enqueue(CodingFollowUp candidate) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        return unitOfWork.execute(() -> {
            CodingSessionMapper mapper = mapper();
            CodingFollowUpRow existing =
                    mapper.findFollowUpByIdempotency(candidate.sessionId().value(), candidate.idempotencyKeyDigest());
            if (existing != null) {
                CodingFollowUp value = followUp(existing);
                if (!value.requestDigest().equals(candidate.requestDigest())) {
                    throw conflict("follow-up idempotency key is bound to another request");
                }
                return value;
            }
            long sequence = mapper.nextFollowUpSequence(candidate.sessionId().value());
            CodingContentPayloadCodec.ProtectedContent payload = content.encode(
                    candidate.message(),
                    candidate.attachments(),
                    followUpBinding(candidate.followUpId(), candidate.requestDigest()));
            requireOne(
                    mapper.insertFollowUp(new CodingFollowUpRow(
                            candidate.followUpId(),
                            SCHEMA_VERSION,
                            candidate.sessionId().value(),
                            candidate.boundRunId().value(),
                            payload.nonce(),
                            payload.ciphertext(),
                            payload.digest(),
                            candidate.idempotencyKeyDigest(),
                            candidate.requestDigest(),
                            candidate.dispatchKey(),
                            candidate.status().name(),
                            sequence,
                            candidate.dispatchedRunId().map(AgentRunId::value).orElse(null),
                            candidate.createdAt(),
                            candidate.updatedAt(),
                            null,
                            null,
                            candidate.revision())),
                    "follow-up insert");
            return requireFollowUp(mapper, candidate.followUpId());
        });
    }

    @Override
    public Optional<CodingFollowUp> findFollowUp(String followUpId) {
        return unitOfWork.execute(
                () -> Optional.ofNullable(mapper().findFollowUp(followUpId)).map(this::followUp));
    }

    @Override
    public Optional<CodingFollowUp> findFollowUpByDispatchKey(String dispatchKey) {
        return unitOfWork.execute(() -> Optional.ofNullable(mapper().findFollowUpByDispatchKey(dispatchKey))
                .map(this::followUp));
    }

    @Override
    public Optional<CodingDispatchClaim> claimNextForDispatch(
            AgentSessionId sessionId, long expectedActivityRevision, Instant updatedAt) {
        return unitOfWork.execute(() -> {
            CodingSessionMapper mapper = mapper();
            CodingSessionActivity activity = requireActivity(mapper, sessionId);
            CodingFollowUpRow row = mapper.findDispatchableFollowUp(sessionId.value());
            if (row == null) return Optional.empty();
            CodingFollowUp current = followUp(row);
            if (current.status() == CodingFollowUpStatus.CLAIMED) {
                if (activity.activeDispatchKey()
                        .filter(current.dispatchKey()::equals)
                        .isEmpty()) {
                    throw conflict("claimed follow-up has no matching active dispatch");
                }
                return Optional.of(new CodingDispatchClaim(activity, current));
            }
            if (activity.revision() != expectedActivityRevision
                    || activity.activeRunId().isPresent()
                    || activity.activeDispatchKey().isPresent()) {
                throw conflict("coding session changed before follow-up claim");
            }
            requireOne(mapper.claimFollowUp(current.followUpId(), current.revision(), updatedAt), "follow-up claim");
            requireOne(
                    mapper.reserveActive(sessionId.value(), expectedActivityRevision, current.dispatchKey(), updatedAt),
                    "follow-up active reservation");
            return Optional.of(new CodingDispatchClaim(
                    requireActivity(mapper, sessionId), requireFollowUp(mapper, current.followUpId())));
        });
    }

    @Override
    public CodingFollowUp markDispatched(
            String followUpId, long expectedRevision, AgentRunId runId, Instant updatedAt) {
        return unitOfWork.execute(() -> {
            CodingSessionMapper mapper = mapper();
            CodingFollowUp current = requireFollowUp(mapper, followUpId);
            if (current.status() == CodingFollowUpStatus.DISPATCHED
                    && current.dispatchedRunId().filter(runId::equals).isPresent()) {
                return current;
            }
            requireOne(
                    mapper.markFollowUpDispatched(followUpId, expectedRevision, runId.value(), updatedAt),
                    "follow-up dispatch completion");
            return requireFollowUp(mapper, followUpId);
        });
    }

    @Override
    public CodingFollowUp restore(String followUpId, long expectedRevision, Instant updatedAt) {
        return unitOfWork.execute(() -> {
            CodingSessionMapper mapper = mapper();
            CodingFollowUp current = requireFollowUp(mapper, followUpId);
            if (current.status() == CodingFollowUpStatus.RESTORED) return current;
            CodingSessionActivity activity = requireActivity(mapper, current.sessionId());
            if (activity.activeDispatchKey()
                    .filter(current.dispatchKey()::equals)
                    .isPresent()) {
                throw conflict("follow-up is already reserved for dispatch");
            }
            requireOne(mapper.restoreFollowUp(followUpId, expectedRevision, updatedAt), "follow-up restore");
            return requireFollowUp(mapper, followUpId);
        });
    }

    @Override
    public int queuedCount(AgentSessionId sessionId) {
        return unitOfWork.execute(() -> mapper().queuedCount(sessionId.value()));
    }

    private CodingSessionMapper mapper() {
        return unitOfWork.mapper(CodingSessionMapper.class);
    }

    private CodingCommandBinding command(CodingSessionCommandRow row) {
        requireSchema(row.schemaVersion());
        CodingContentPayloadCodec.Content value = content.decode(
                row.contentNonce(),
                row.contentCiphertext(),
                row.contentDigest(),
                CodingContentPayloadCodec.binding(
                        "command",
                        row.callerScopeDigest() + "|" + row.operation() + "|" + row.idempotencyKeyDigest(),
                        row.requestDigest()));
        return new CodingCommandBinding(
                row.callerScopeDigest(),
                row.operation(),
                row.idempotencyKeyDigest(),
                row.requestDigest(),
                row.dispatchKey(),
                new AgentSessionId(row.sessionId()),
                new ProjectId(row.projectId()),
                value.message(),
                value.attachments(),
                Optional.ofNullable(row.runId()).map(AgentRunId::new),
                row.createdAt());
    }

    private CodingSessionActivity activity(CodingSessionActivityRow row) {
        requireSchema(row.schemaVersion());
        return new CodingSessionActivity(
                new AgentSessionId(row.sessionId()),
                new ProjectId(row.projectId()),
                new TenantRef(row.tenantId()),
                new PrincipalRef(row.principalId(), row.principalType()),
                row.displayName(),
                Optional.ofNullable(row.activeRunId()).map(AgentRunId::new),
                row.activeRunVersion() == null ? OptionalLong.empty() : OptionalLong.of(row.activeRunVersion()),
                Optional.ofNullable(row.activeDispatchKey()),
                row.createdAt(),
                row.lastActivityAt(),
                row.revision());
    }

    private CodingFollowUp followUp(CodingFollowUpRow row) {
        requireSchema(row.schemaVersion());
        CodingContentPayloadCodec.Content value = content.decode(
                row.contentNonce(),
                row.contentCiphertext(),
                row.contentDigest(),
                followUpBinding(row.followUpId(), row.requestDigest()));
        return new CodingFollowUp(
                row.followUpId(),
                new AgentSessionId(row.sessionId()),
                new AgentRunId(row.boundRunId()),
                value.message(),
                value.attachments(),
                row.idempotencyKeyDigest(),
                row.requestDigest(),
                row.dispatchKey(),
                CodingFollowUpStatus.valueOf(row.status()),
                row.sequence(),
                Optional.ofNullable(row.dispatchedRunId()).map(AgentRunId::new),
                row.createdAt(),
                row.updatedAt(),
                row.revision());
    }

    private static CodingSessionActivityRow activityRow(CodingSessionActivity value) {
        return new CodingSessionActivityRow(
                value.sessionId().value(),
                SCHEMA_VERSION,
                value.projectId().value(),
                value.tenant().tenantId(),
                value.principal().principalId(),
                value.principal().principalType(),
                value.displayName(),
                value.activeRunId().map(AgentRunId::value).orElse(null),
                value.activeRunVersion().isPresent() ? value.activeRunVersion().orElseThrow() : null,
                value.activeDispatchKey().orElse(null),
                value.createdAt(),
                value.lastActivityAt(),
                value.revision());
    }

    private CodingSessionActivity requireActivity(CodingSessionMapper mapper, AgentSessionId sessionId) {
        CodingSessionActivityRow row = mapper.findActivity(sessionId.value());
        if (row == null) throw conflict("coding session activity is unavailable");
        return activity(row);
    }

    private CodingFollowUp requireFollowUp(CodingSessionMapper mapper, String followUpId) {
        CodingFollowUpRow row = mapper.findFollowUp(followUpId);
        if (row == null) throw conflict("follow-up is unavailable");
        return followUp(row);
    }

    private static CodingSessionCommandRow requireCommand(CodingSessionCommandRow row) {
        if (row == null) throw conflict("coding command is unavailable");
        return row;
    }

    private static String commandBinding(CodingCommandBinding value) {
        return CodingContentPayloadCodec.binding(
                "command",
                value.callerScopeDigest() + "|" + value.operation() + "|" + value.idempotencyKeyDigest(),
                value.requestDigest());
    }

    private static String followUpBinding(String followUpId, String requestDigest) {
        return CodingContentPayloadCodec.binding("follow-up", followUpId, requestDigest);
    }

    private static String like(String value) {
        return "%"
                + value.toLowerCase(java.util.Locale.ROOT)
                        .replace("\\", "\\\\")
                        .replace("%", "\\%")
                        .replace("_", "\\_") + "%";
    }

    private static void requireSchema(String value) {
        if (!SCHEMA_VERSION.equals(value)) throw conflict("unsupported Coding Session schema");
    }

    private static void requireOne(int count, String operation) {
        if (count != 1) throw conflict(operation + " lost an optimistic concurrency race");
    }

    private static IllegalStateException conflict(String message) {
        return new IllegalStateException(message);
    }
}

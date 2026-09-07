package io.haifa.agent.store.sqlite;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionRequestId;
import io.haifa.agent.runtime.api.InteractionResponse;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseSubmission;
import io.haifa.agent.runtime.api.InteractionResponseType;
import io.haifa.agent.runtime.api.InteractionState;
import io.haifa.agent.runtime.api.RuntimeApiErrorCode;
import io.haifa.agent.runtime.api.RuntimeContractException;
import io.haifa.agent.runtime.core.bootstrap.RuntimeCallerContext;
import io.haifa.agent.runtime.core.idempotency.CanonicalRequestDigest;
import io.haifa.agent.runtime.core.interaction.InteractionExpirationOutcome;
import io.haifa.agent.runtime.core.interaction.InteractionPort;
import io.haifa.agent.runtime.core.interaction.InteractionRecord;
import io.haifa.agent.runtime.core.interaction.InteractionRequest;
import io.haifa.agent.runtime.core.interaction.InteractionResolution;
import io.haifa.agent.runtime.core.interaction.InteractionSemantics;
import io.haifa.agent.runtime.core.interaction.InteractionSubmissionResolution;
import io.haifa.agent.runtime.core.interaction.ResolvedInteraction;
import io.haifa.agent.runtime.core.interaction.ToolApprovalTarget;
import io.haifa.agent.store.sqlite.codec.EncodedPayload;
import io.haifa.agent.store.sqlite.codec.VersionedPayloadCodecRegistry;
import io.haifa.agent.store.sqlite.mybatis.InteractionRequestRow;
import io.haifa.agent.store.sqlite.mybatis.InteractionResponseRow;
import io.haifa.agent.store.sqlite.mybatis.RuntimeStoreMapper;
import io.haifa.agent.store.sqlite.payload.ContentPartsPayload;
import io.haifa.agent.store.sqlite.payload.InteractionTargetPayload;
import io.haifa.agent.store.sqlite.payload.SqliteRuntimePayloadTypes;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class SqliteInteractionPort implements InteractionPort {
    private final SqliteRuntimeUnitOfWork unitOfWork;
    private final VersionedPayloadCodecRegistry codecs;
    private final Clock clock;
    private final int maximumPayloadBytes;

    public SqliteInteractionPort(
            SqliteRuntimeUnitOfWork unitOfWork,
            VersionedPayloadCodecRegistry codecs,
            Clock clock,
            int maximumPayloadBytes) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        this.codecs = Objects.requireNonNull(codecs, "codecs must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (maximumPayloadBytes < 1) throw new IllegalArgumentException("maximumPayloadBytes must be positive");
        this.maximumPayloadBytes = maximumPayloadBytes;
    }

    @Override
    public void create(InteractionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.prompt().getBytes(StandardCharsets.UTF_8).length > maximumPayloadBytes) {
            throw new IllegalArgumentException("interaction prompt exceeds configured size limit");
        }
        execute(() -> {
            RuntimeStoreMapper mapper = unitOfWork.mapper(RuntimeStoreMapper.class);
            EncodedPayload target = codecs.encode(
                    SqliteRuntimePayloadTypes.INTERACTION_TARGET, InteractionTargetPayload.from(request.target()));
            String targetKind = request.target() instanceof ToolApprovalTarget ? "tool-approval" : "generic";
            mapper.insertInteractionRequest(new InteractionRequestRow(
                    request.id().value(),
                    request.runId().value(),
                    request.tenant().tenantId(),
                    request.requester().principalId(),
                    request.requester().principalType(),
                    request.type(),
                    request.prompt(),
                    request.approval(),
                    targetKind,
                    target.schemaVersion(),
                    target.bytes(),
                    target.hash(),
                    request.createdAt(),
                    request.expiresAt().orElse(null),
                    0L,
                    InteractionSemantics.kind(request).value(),
                    InteractionState.PENDING.name(),
                    request.expirationOutcome().name(),
                    null,
                    null));
            mapper.insertInteractionApplication(request.id().value());
            return null;
        });
    }

    @Override
    public Optional<InteractionRequest> pending(AgentRunId runId) {
        return execute(() -> Optional.ofNullable(
                        unitOfWork.mapper(RuntimeStoreMapper.class).pendingInteraction(runId.value()))
                .map(this::fromRequestRow));
    }

    @Override
    public Optional<InteractionRequest> find(InteractionRequestId requestId) {
        return execute(() -> Optional.ofNullable(
                        unitOfWork.mapper(RuntimeStoreMapper.class).findInteractionRequest(requestId.value()))
                .map(this::fromRequestRow));
    }

    @Override
    public Optional<InteractionRecord> record(InteractionRequestId requestId) {
        Objects.requireNonNull(requestId, "requestId must not be null");
        return execute(() -> Optional.ofNullable(
                        unitOfWork.mapper(RuntimeStoreMapper.class).findInteractionRequest(requestId.value()))
                .map(this::fromRecordRow));
    }

    @Override
    public Optional<InteractionRecord> pendingRecord(AgentRunId runId) {
        Objects.requireNonNull(runId, "runId must not be null");
        return execute(() -> Optional.ofNullable(
                        unitOfWork.mapper(RuntimeStoreMapper.class).pendingInteraction(runId.value()))
                .map(this::fromRecordRow));
    }

    @Override
    public Optional<ResolvedInteraction> unappliedToolResolution(AgentRunId runId) {
        return execute(() -> {
            RuntimeStoreMapper mapper = unitOfWork.mapper(RuntimeStoreMapper.class);
            InteractionRequestRow request = mapper.unappliedToolResolution(runId.value());
            if (request == null) return Optional.empty();
            InteractionResponseRow response = mapper.findInteractionResponseForRequest(request.requestId());
            if (response == null) throw new IllegalStateException("resolved interaction response is missing");
            return Optional.of(new ResolvedInteraction(fromRequestRow(request), fromResponseRow(response)));
        });
    }

    @Override
    public void markResolutionApplied(InteractionRequestId requestId) {
        execute(() -> {
            RuntimeStoreMapper mapper = unitOfWork.mapper(RuntimeStoreMapper.class);
            InteractionRecord current = requireRecord(mapper, requestId);
            if (current.state() == InteractionState.APPLIED) return null;
            if (current.state() != InteractionState.RESPONDED) {
                throw new IllegalArgumentException("interaction is not resolved");
            }
            Instant appliedAt = Instant.ofEpochMilli(clock.millis());
            if (mapper.markInteractionApplied(requestId.value(), appliedAt) != 1) {
                if (requireRecord(mapper, requestId).state() == InteractionState.APPLIED) return null;
                throw new IllegalStateException("interaction application state changed concurrently");
            }
            if (mapper.markInteractionStateApplied(requestId.value(), current.revision(), appliedAt) != 1) {
                throw new RuntimeContractException(
                        RuntimeApiErrorCode.INTERACTION_REVISION_CONFLICT,
                        "The interaction revision is no longer current");
            }
            return null;
        });
    }

    @Override
    public InteractionResolution respond(
            InteractionResponse response, RuntimeCallerContext caller, Instant receivedAt) {
        Objects.requireNonNull(response, "response must not be null");
        Objects.requireNonNull(caller, "caller must not be null");
        Objects.requireNonNull(receivedAt, "receivedAt must not be null");
        return execute(() -> {
            RuntimeStoreMapper mapper = unitOfWork.mapper(RuntimeStoreMapper.class);
            InteractionResponseRow existing =
                    mapper.findInteractionResponse(response.responseId().value());
            if (existing != null) {
                if (!fromResponseRow(existing).equals(response)) {
                    throw new IllegalStateException("response id is already used");
                }
                return new InteractionResolution(requireRequest(mapper, response.requestId()), false);
            }
            InteractionRequest request = requireRequest(mapper, response.requestId());
            validateResponse(request, response, caller, receivedAt);
            InteractionRecord current = requireRecord(mapper, request.id());
            if (current.state() != InteractionState.PENDING
                    || mapper.findInteractionResponseForRequest(request.id().value()) != null) {
                throw new IllegalStateException("interaction already has a response");
            }
            EncodedPayload inputs =
                    codecs.encode(SqliteRuntimePayloadTypes.CONTENT_PARTS, ContentPartsPayload.from(response.inputs()));
            InteractionAction action = toAction(response.type());
            InteractionResponseSubmission canonical = new InteractionResponseSubmission(
                    response.responseId(),
                    response.requestId(),
                    response.runId(),
                    current.revision(),
                    action,
                    response.inputs(),
                    response.idempotencyKey(),
                    response.respondedAt());
            mapper.insertInteractionResponse(new InteractionResponseRow(
                    response.responseId().value(),
                    response.requestId().value(),
                    response.runId().value(),
                    response.type().name(),
                    inputs.schemaVersion(),
                    inputs.bytes(),
                    inputs.hash(),
                    response.idempotencyKey(),
                    response.respondedAt(),
                    receivedAt,
                    action.value(),
                    current.revision(),
                    callerScope(caller),
                    CanonicalRequestDigest.interactionResponse(canonical),
                    caller.tenant().tenantId(),
                    caller.principal().principalId(),
                    caller.principal().principalType(),
                    "ACCEPTED"));
            if (mapper.markInteractionResponded(request.id().value(), current.revision(), receivedAt) != 1) {
                throw new IllegalStateException("interaction already has a response");
            }
            return new InteractionResolution(request, true);
        });
    }

    @Override
    public InteractionSubmissionResolution respond(
            InteractionResponseSubmission response, RuntimeCallerContext caller, Instant receivedAt) {
        Objects.requireNonNull(response, "response must not be null");
        Objects.requireNonNull(caller, "caller must not be null");
        Objects.requireNonNull(receivedAt, "receivedAt must not be null");
        String scope = callerScope(caller);
        String digest = CanonicalRequestDigest.interactionResponse(response);
        return execute(() -> {
            RuntimeStoreMapper mapper = unitOfWork.mapper(RuntimeStoreMapper.class);
            InteractionResponseRow existing = mapper.findInteractionResponseByIdempotency(
                    scope, response.requestId().value(), response.idempotencyKey());
            if (existing != null) {
                if (!digest.equals(existing.canonicalDigest())) {
                    throw new RuntimeContractException(
                            RuntimeApiErrorCode.IDEMPOTENCY_CONFLICT,
                            "The idempotency key is already bound to a different interaction response");
                }
                return new InteractionSubmissionResolution(requireRecord(mapper, response.requestId()), false);
            }
            existing = mapper.findInteractionResponse(response.responseId().value());
            if (existing != null) {
                if (!digest.equals(existing.canonicalDigest())) {
                    throw new RuntimeContractException(
                            RuntimeApiErrorCode.IDEMPOTENCY_CONFLICT,
                            "The response id is already bound to different content");
                }
                return new InteractionSubmissionResolution(requireRecord(mapper, response.requestId()), false);
            }
            InteractionRequest request = requireRequest(mapper, response.requestId());
            validateCallerAndRequest(request, response.runId(), caller, receivedAt);
            if (receivedAt.isBefore(response.respondedAt())) {
                throw new IllegalArgumentException("receivedAt must not precede respondedAt");
            }
            InteractionRecord current = requireRecord(mapper, request.id());
            if (current.revision() != response.expectedRevision()) {
                throw new RuntimeContractException(
                        RuntimeApiErrorCode.INTERACTION_REVISION_CONFLICT,
                        "The interaction revision is no longer current");
            }
            if (current.state() != InteractionState.PENDING) {
                throw new RuntimeContractException(
                        RuntimeApiErrorCode.INTERACTION_ALREADY_RESOLVED, "The interaction is already resolved");
            }
            validateActionAndInput(request, response);
            EncodedPayload inputs =
                    codecs.encode(SqliteRuntimePayloadTypes.CONTENT_PARTS, ContentPartsPayload.from(response.inputs()));
            mapper.insertInteractionResponse(new InteractionResponseRow(
                    response.responseId().value(),
                    response.requestId().value(),
                    response.runId().value(),
                    toLegacyType(response.action()).name(),
                    inputs.schemaVersion(),
                    inputs.bytes(),
                    inputs.hash(),
                    response.idempotencyKey(),
                    response.respondedAt(),
                    receivedAt,
                    response.action().value(),
                    response.expectedRevision(),
                    scope,
                    digest,
                    caller.tenant().tenantId(),
                    caller.principal().principalId(),
                    caller.principal().principalType(),
                    "ACCEPTED"));
            if (mapper.markInteractionResponded(request.id().value(), current.revision(), receivedAt) != 1) {
                throw new RuntimeContractException(
                        RuntimeApiErrorCode.INTERACTION_REVISION_CONFLICT,
                        "The interaction revision is no longer current");
            }
            return new InteractionSubmissionResolution(requireRecord(mapper, request.id()), true);
        });
    }

    @Override
    public List<InteractionRecord> due(AgentRunId runId, Instant at, int limit) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(at, "at must not be null");
        if (limit < 1 || limit > 1_000) throw new IllegalArgumentException("limit must be in 1..1000");
        return execute(
                () -> unitOfWork.mapper(RuntimeStoreMapper.class).dueInteractions(runId.value(), at, limit).stream()
                        .map(this::fromRecordRow)
                        .toList());
    }

    @Override
    public InteractionRecord expire(InteractionRequestId requestId, long expectedRevision, Instant at) {
        InteractionRecord current = requireRecord(requestId);
        if (current.revision() != expectedRevision) revisionConflict();
        if (current.state() != InteractionState.PENDING) return current;
        Instant expiresAt = current.request()
                .expiresAt()
                .orElseThrow(() -> new IllegalArgumentException("interaction does not expire automatically"));
        if (at.isBefore(expiresAt)) {
            throw new IllegalArgumentException("interaction has not expired");
        }
        return transition(
                current,
                expectedRevision,
                InteractionState.PENDING,
                InteractionState.EXPIRED,
                "INTERACTION_EXPIRED",
                at);
    }

    @Override
    public InteractionRecord cancel(
            InteractionRequestId requestId, long expectedRevision, String reasonCode, Instant at) {
        return transition(
                requireRecord(requestId),
                expectedRevision,
                InteractionState.PENDING,
                InteractionState.CANCELLED,
                requireReason(reasonCode),
                at);
    }

    @Override
    public InteractionRecord invalidate(
            InteractionRequestId requestId, long expectedRevision, String reasonCode, Instant at) {
        InteractionRecord current = requireRecord(requestId);
        if (current.state() != InteractionState.PENDING && current.state() != InteractionState.RESPONDED) {
            if (current.revision() != expectedRevision) revisionConflict();
            return current;
        }
        return transition(
                current,
                expectedRevision,
                current.state(),
                InteractionState.INVALIDATED,
                requireReason(reasonCode),
                at);
    }

    private void validateResponse(
            InteractionRequest request, InteractionResponse response, RuntimeCallerContext caller, Instant receivedAt) {
        if (!request.runId().equals(response.runId())) {
            throw new IllegalArgumentException("response run does not match request");
        }
        if (!request.tenant().equals(caller.tenant()) || !request.requester().equals(caller.principal())) {
            throw new SecurityException("caller cannot respond to this interaction");
        }
        if (request.expiresAt()
                .map(expiresAt -> !receivedAt.isBefore(expiresAt))
                .orElse(false)) {
            throw new IllegalStateException("interaction has expired");
        }
        if (receivedAt.isBefore(response.respondedAt())) {
            throw new IllegalArgumentException("receivedAt must not precede respondedAt");
        }
        if (request.approval() && response.type() == InteractionResponseType.CLARIFY) {
            throw new IllegalArgumentException("approval interaction requires approve or reject");
        }
        if (!request.approval() && response.type() != InteractionResponseType.CLARIFY) {
            throw new IllegalArgumentException("clarification interaction requires clarify");
        }
    }

    private InteractionRequest requireRequest(RuntimeStoreMapper mapper, InteractionRequestId requestId) {
        InteractionRequestRow row = mapper.findInteractionRequest(requestId.value());
        if (row == null) throw new IllegalArgumentException("unknown interaction request");
        return fromRequestRow(row);
    }

    private InteractionRecord requireRecord(InteractionRequestId requestId) {
        return execute(() -> requireRecord(unitOfWork.mapper(RuntimeStoreMapper.class), requestId));
    }

    private InteractionRecord requireRecord(RuntimeStoreMapper mapper, InteractionRequestId requestId) {
        InteractionRequestRow row = mapper.findInteractionRequest(requestId.value());
        if (row == null) {
            throw new RuntimeContractException(
                    RuntimeApiErrorCode.INTERACTION_NOT_FOUND, "The interaction does not exist or is not visible");
        }
        return fromRecordRow(row);
    }

    private InteractionRecord fromRecordRow(InteractionRequestRow row) {
        InteractionRequest request = fromRequestRow(row);
        InteractionState state;
        InteractionExpirationOutcome expirationOutcome;
        try {
            state = InteractionState.valueOf(row.state());
            expirationOutcome = InteractionExpirationOutcome.valueOf(row.expirationOutcome());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("unknown persisted interaction state", exception);
        }
        if (expirationOutcome != request.expirationOutcome()) {
            throw new IllegalStateException("interaction expiration outcome columns do not match request");
        }
        InteractionResponseRow response =
                unitOfWork.mapper(RuntimeStoreMapper.class).findInteractionResponseForRequest(row.requestId());
        Optional<InteractionResponseId> responseId =
                response == null ? Optional.empty() : Optional.of(new InteractionResponseId(response.responseId()));
        Optional<InteractionAction> action =
                response == null ? Optional.empty() : Optional.of(new InteractionAction(response.action()));
        return new InteractionRecord(
                request,
                row.revision(),
                state,
                responseId,
                action,
                Optional.ofNullable(row.stateReasonCode()),
                Optional.ofNullable(row.stateChangedAt()));
    }

    private InteractionRequest fromRequestRow(InteractionRequestRow row) {
        InteractionTargetPayload target = codecs.decode(
                SqliteRuntimePayloadTypes.INTERACTION_TARGET,
                new EncodedPayload(
                        SqliteRuntimePayloadTypes.INTERACTION_TARGET.name(),
                        row.targetSchemaVersion(),
                        row.targetPayload(),
                        row.targetHash()));
        String expectedKind = "tool-approval".equals(row.targetType()) ? "tool-approval" : "generic";
        if (!expectedKind.equals(target.kind())) {
            throw new IllegalStateException("interaction target discriminator does not match payload");
        }
        return new InteractionRequest(
                new InteractionRequestId(row.requestId()),
                new AgentRunId(row.runId()),
                new TenantRef(row.tenantId()),
                new PrincipalRef(row.principalId(), row.principalType()),
                row.type(),
                row.prompt(),
                row.approval(),
                target.toDomain(),
                row.createdAt(),
                Optional.ofNullable(row.expiresAt()),
                InteractionExpirationOutcome.valueOf(row.expirationOutcome()));
    }

    private InteractionRecord transition(
            InteractionRecord current,
            long expectedRevision,
            InteractionState expectedState,
            InteractionState targetState,
            String reasonCode,
            Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        if (current.revision() != expectedRevision) revisionConflict();
        if (current.state() != expectedState) return current;
        return execute(() -> {
            RuntimeStoreMapper mapper = unitOfWork.mapper(RuntimeStoreMapper.class);
            if (mapper.transitionInteractionState(
                            current.request().id().value(),
                            expectedRevision,
                            expectedState.name(),
                            targetState.name(),
                            reasonCode,
                            at)
                    != 1) {
                revisionConflict();
            }
            return requireRecord(mapper, current.request().id());
        });
    }

    private InteractionResponse fromResponseRow(InteractionResponseRow row) {
        ContentPartsPayload inputs = codecs.decode(
                SqliteRuntimePayloadTypes.CONTENT_PARTS,
                new EncodedPayload(
                        SqliteRuntimePayloadTypes.CONTENT_PARTS.name(),
                        row.inputsSchemaVersion(),
                        row.inputsPayload(),
                        row.inputsHash()));
        return new InteractionResponse(
                new InteractionResponseId(row.responseId()),
                new InteractionRequestId(row.requestId()),
                new AgentRunId(row.runId()),
                InteractionResponseType.valueOf(row.responseType()),
                inputs.toDomain(),
                row.idempotencyKey(),
                row.respondedAt());
    }

    private static void validateActionAndInput(InteractionRequest request, InteractionResponseSubmission response) {
        if (!InteractionSemantics.allowedActions(InteractionSemantics.kind(request))
                .contains(response.action())) {
            throw new RuntimeContractException(
                    RuntimeApiErrorCode.INTERACTION_ACTION_NOT_ALLOWED,
                    "The action is not allowed for this interaction");
        }
        if (response.action().equals(InteractionAction.SUBMIT)
                && response.inputs().isEmpty()) {
            throw new IllegalArgumentException("the selected action requires bounded response content");
        }
        if (!response.action().equals(InteractionAction.SUBMIT)
                && !response.inputs().isEmpty()) {
            throw new IllegalArgumentException("the selected action does not accept response content");
        }
    }

    private void validateCallerAndRequest(
            InteractionRequest request, AgentRunId responseRunId, RuntimeCallerContext caller, Instant receivedAt) {
        if (!request.runId().equals(responseRunId)
                || !request.tenant().equals(caller.tenant())
                || !request.requester().equals(caller.principal())) {
            throw new RuntimeContractException(
                    RuntimeApiErrorCode.INTERACTION_NOT_FOUND, "The interaction does not exist or is not visible");
        }
        if (request.expiresAt()
                .map(expiresAt -> !receivedAt.isBefore(expiresAt))
                .orElse(false)) {
            throw new RuntimeContractException(RuntimeApiErrorCode.INTERACTION_EXPIRED, "The interaction has expired");
        }
    }

    private static InteractionResponseType toLegacyType(InteractionAction action) {
        if (action.equals(InteractionAction.APPROVE)) return InteractionResponseType.APPROVE;
        if (action.equals(InteractionAction.REJECT) || action.equals(InteractionAction.CANCEL)) {
            return InteractionResponseType.REJECT;
        }
        return InteractionResponseType.CLARIFY;
    }

    private static InteractionAction toAction(InteractionResponseType type) {
        return switch (type) {
            case APPROVE -> InteractionAction.APPROVE;
            case REJECT -> InteractionAction.REJECT;
            case CLARIFY -> InteractionAction.SUBMIT;
        };
    }

    private static String callerScope(RuntimeCallerContext caller) {
        return caller.tenant().tenantId() + "|" + caller.principal().principalType() + "|"
                + caller.principal().principalId();
    }

    private static String requireReason(String reasonCode) {
        String normalized = Objects.requireNonNull(reasonCode, "reasonCode must not be null")
                .trim();
        if (normalized.isEmpty() || normalized.length() > 128 || !normalized.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException("reasonCode must be a bounded upper-snake token");
        }
        return normalized;
    }

    private static void revisionConflict() {
        throw new RuntimeContractException(
                RuntimeApiErrorCode.INTERACTION_REVISION_CONFLICT, "The interaction revision is no longer current");
    }

    private <T> T execute(Supplier<T> work) {
        try {
            return unitOfWork.execute(work);
        } catch (SqliteStoreException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) throw runtimeException;
            throw exception;
        }
    }
}

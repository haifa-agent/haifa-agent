package io.haifa.agent.store.sqlite;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.policy.api.ApprovalAuthorityRequirementRef;
import io.haifa.agent.policy.api.ApprovalRequestContext;
import io.haifa.agent.policy.api.ApprovalVerification;
import io.haifa.agent.policy.api.PolicyDigest;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.runtime.api.InteractionRequestId;
import io.haifa.agent.runtime.api.InteractionResponse;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseType;
import io.haifa.agent.runtime.core.bootstrap.RuntimeCallerContext;
import io.haifa.agent.runtime.core.interaction.InteractionPort;
import io.haifa.agent.runtime.core.interaction.InteractionRequest;
import io.haifa.agent.runtime.core.interaction.InteractionResolution;
import io.haifa.agent.runtime.core.interaction.ResolvedInteraction;
import io.haifa.agent.runtime.core.interaction.ToolApprovalTarget;
import io.haifa.agent.store.sqlite.codec.EncodedPayload;
import io.haifa.agent.store.sqlite.codec.VersionedPayloadCodecRegistry;
import io.haifa.agent.store.sqlite.mybatis.ApprovalRequestMetadataRow;
import io.haifa.agent.store.sqlite.mybatis.InteractionRequestRow;
import io.haifa.agent.store.sqlite.mybatis.InteractionResponseRow;
import io.haifa.agent.store.sqlite.mybatis.PolicyDecisionRow;
import io.haifa.agent.store.sqlite.mybatis.PolicyStoreMapper;
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
                    request.expiresAt()));
            mapper.insertInteractionApplication(request.id().value());
            request.approvalContext().ifPresent(context -> insertApprovalMetadata(request, context));
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
            InteractionRequestRow request = mapper.findInteractionRequest(requestId.value());
            if (request == null) throw new IllegalArgumentException("unknown interaction request");
            InteractionResponseRow response = mapper.findInteractionResponseForRequest(requestId.value());
            if (response == null) throw new IllegalArgumentException("interaction is not resolved");
            mapper.markInteractionApplied(requestId.value(), clock.instant());
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
            if (mapper.findInteractionResponseForRequest(request.id().value()) != null) {
                throw new IllegalStateException("interaction already has a response");
            }
            EncodedPayload inputs =
                    codecs.encode(SqliteRuntimePayloadTypes.CONTENT_PARTS, ContentPartsPayload.from(response.inputs()));
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
                    receivedAt));
            if (request.approvalContext().isPresent()) {
                String selectedScope = request.approvalContext()
                                        .orElseThrow()
                                        .allowedReuseScopes()
                                        .size()
                                == 1
                        ? request.approvalContext()
                                .orElseThrow()
                                .allowedReuseScopes()
                                .iterator()
                                .next()
                                .name()
                        : null;
                String validationDigest = PolicyDigest.sha256Fields(List.of(
                        response.responseId().value(),
                        request.id().value(),
                        caller.tenant().tenantId(),
                        caller.principal().principalType(),
                        caller.principal().principalId()));
                unitOfWork
                        .mapper(PolicyStoreMapper.class)
                        .insertApprovalResponseMetadata(
                                response.responseId().value(),
                                caller.tenant().tenantId(),
                                caller.principal().principalId(),
                                caller.principal().principalType(),
                                "PENDING",
                                "VERIFICATION_PENDING",
                                "PENDING",
                                "VERIFICATION_PENDING",
                                selectedScope,
                                validationDigest);
            }
            return new InteractionResolution(request, true);
        });
    }

    @Override
    public void recordApprovalVerification(InteractionResponseId responseId, ApprovalVerification verification) {
        Objects.requireNonNull(responseId, "responseId must not be null");
        Objects.requireNonNull(verification, "verification must not be null");
        execute(() -> {
            String outcome = verification.accepted() ? "ACCEPTED" : "REJECTED";
            if (unitOfWork
                            .mapper(PolicyStoreMapper.class)
                            .updateApprovalResponseVerification(responseId.value(), outcome, verification.reasonCode())
                    != 1) {
                throw new IllegalStateException("approval response metadata is missing");
            }
            return null;
        });
    }

    private void validateResponse(
            InteractionRequest request, InteractionResponse response, RuntimeCallerContext caller, Instant receivedAt) {
        if (!request.runId().equals(response.runId())) {
            throw new IllegalArgumentException("response run does not match request");
        }
        if (!request.tenant().equals(caller.tenant())
                || (request.approvalContext().isEmpty() && !request.requester().equals(caller.principal()))) {
            throw new SecurityException("caller cannot respond to this interaction");
        }
        if (receivedAt.isAfter(request.expiresAt())) throw new IllegalStateException("interaction has expired");
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
        Optional<ApprovalRequestContext> approvalContext = Optional.ofNullable(
                        unitOfWork.mapper(PolicyStoreMapper.class).findApprovalRequestMetadata(row.requestId()))
                .map(this::approvalContext);
        if (approvalContext.isPresent() && !row.approval()) {
            throw new IllegalStateException("non-approval interaction cannot carry approval metadata");
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
                row.expiresAt(),
                approvalContext);
    }

    private void insertApprovalMetadata(InteractionRequest request, ApprovalRequestContext context) {
        PolicyStoreMapper mapper = unitOfWork.mapper(PolicyStoreMapper.class);
        PolicyDecisionRow decision =
                mapper.findPolicyDecision(context.decisionId().value());
        if (decision == null) throw new IllegalStateException("approval policy decision is missing");
        if (PolicyEffect.valueOf(decision.effect()) != PolicyEffect.ASK || decision.challenge() == null) {
            throw new IllegalStateException("approval policy decision is not an ASK decision");
        }
        EncodedPayload payload = codecs.encode(SqliteRuntimePayloadTypes.APPROVAL_REQUEST_CONTEXT, context);
        ApprovalAuthorityRequirementRef authority =
                context.authorityRequirement().orElse(null);
        mapper.insertApprovalRequestMetadata(new ApprovalRequestMetadataRow(
                request.id().value(),
                context.decisionId().value(),
                context.semantics().name(),
                decision.challenge(),
                context.requester().tenant().tenantId(),
                context.requester().principal().principalId(),
                context.requester().principal().principalType(),
                context.target().targetType(),
                context.target().targetId(),
                context.target().targetVersion(),
                context.target().operation(),
                context.target().targetDigest(),
                context.target().safeSummary(),
                authority == null ? null : authority.providerId(),
                authority == null ? null : authority.requirementId(),
                authority == null ? null : authority.version(),
                context.externalCorrelationRef().orElse(null),
                payload.schemaVersion(),
                payload.bytes(),
                payload.hash()));
    }

    private ApprovalRequestContext approvalContext(ApprovalRequestMetadataRow row) {
        ApprovalRequestContext context = codecs.decode(
                SqliteRuntimePayloadTypes.APPROVAL_REQUEST_CONTEXT,
                new EncodedPayload(
                        SqliteRuntimePayloadTypes.APPROVAL_REQUEST_CONTEXT.name(),
                        row.metadataSchemaVersion(),
                        row.metadataPayload(),
                        row.metadataHash()));
        ApprovalAuthorityRequirementRef authority =
                context.authorityRequirement().orElse(null);
        PolicyDecisionRow decision = unitOfWork.mapper(PolicyStoreMapper.class).findPolicyDecision(row.decisionId());
        if (decision == null
                || PolicyEffect.valueOf(decision.effect()) != PolicyEffect.ASK
                || !Objects.equals(decision.challenge(), row.challenge())) {
            throw new IllegalStateException("approval metadata decision binding is invalid");
        }
        if (!context.decisionId().value().equals(row.decisionId())
                || !context.semantics().name().equals(row.semantics())
                || !context.requester().tenant().tenantId().equals(row.requesterTenantId())
                || !context.requester().principal().principalId().equals(row.requesterPrincipalId())
                || !context.requester().principal().principalType().equals(row.requesterPrincipalType())
                || !context.target().targetType().equals(row.targetType())
                || !context.target().targetId().equals(row.targetId())
                || !context.target().targetVersion().equals(row.targetVersion())
                || !context.target().operation().equals(row.targetOperation())
                || !context.target().targetDigest().equals(row.targetDigest())
                || !context.target().safeSummary().equals(row.targetSafeSummary())
                || !Objects.equals(authority == null ? null : authority.providerId(), row.authorityProviderId())
                || !Objects.equals(authority == null ? null : authority.requirementId(), row.authorityRequirementId())
                || !Objects.equals(authority == null ? null : authority.version(), row.authorityRequirementVersion())
                || !context.externalCorrelationRef().equals(Optional.ofNullable(row.externalCorrelationRef()))) {
            throw new IllegalStateException("approval metadata columns do not match payload");
        }
        return context;
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

    private <T> T execute(Supplier<T> work) {
        try {
            return unitOfWork.execute(work);
        } catch (SqliteStoreException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) throw runtimeException;
            throw exception;
        }
    }
}

package io.haifa.agent.runtime.core.interaction;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionRequestId;
import io.haifa.agent.runtime.api.InteractionResponse;
import io.haifa.agent.runtime.api.InteractionResponseSubmission;
import io.haifa.agent.runtime.api.InteractionResponseType;
import io.haifa.agent.runtime.api.InteractionState;
import io.haifa.agent.runtime.api.RuntimeContractException;
import io.haifa.agent.runtime.api.RuntimeErrorCode;
import io.haifa.agent.runtime.core.bootstrap.RuntimeCallerContext;
import io.haifa.agent.runtime.core.idempotency.CanonicalRequestDigest;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class InMemoryInteractionPort implements InteractionPort {
    private final Map<InteractionRequestId, InteractionRequest> requests = new HashMap<>();
    private final Map<String, InteractionResponse> responses = new HashMap<>();
    private final Map<InteractionRequestId, InteractionResponse> resolved = new HashMap<>();
    private final Map<InteractionRequestId, InteractionRecord> records = new HashMap<>();
    private final Map<String, String> submissionDigests = new HashMap<>();

    @Override
    public synchronized void create(InteractionRequest request) {
        if (requests.containsKey(request.id())) {
            throw new IllegalStateException("interaction already exists");
        }
        boolean blockingPending = records.values().stream()
                .anyMatch(record ->
                        record.request().runId().equals(request.runId()) && record.state() == InteractionState.PENDING);
        if (blockingPending) {
            throw new IllegalStateException("run already has a blocking pending interaction");
        }
        requests.put(request.id(), request);
        records.put(request.id(), InteractionRecord.pending(request));
    }

    @Override
    public synchronized Optional<InteractionRequest> pending(AgentRunId runId) {
        return pendingRecord(runId).map(InteractionRecord::request);
    }

    @Override
    public synchronized Optional<InteractionRequest> find(InteractionRequestId requestId) {
        return Optional.ofNullable(requests.get(requestId));
    }

    @Override
    public synchronized Optional<InteractionRecord> record(InteractionRequestId requestId) {
        return Optional.ofNullable(records.get(requestId));
    }

    @Override
    public synchronized Optional<InteractionRecord> pendingRecord(AgentRunId runId) {
        return records.values().stream()
                .filter(record -> record.request().runId().equals(runId))
                .filter(record -> record.state() == InteractionState.PENDING)
                .sorted(Comparator.comparing(record -> record.request().createdAt()))
                .findFirst();
    }

    @Override
    public synchronized Optional<ResolvedInteraction> unappliedToolResolution(AgentRunId runId) {
        return records.values().stream()
                .filter(record -> record.request().runId().equals(runId))
                .filter(record -> record.request().target() instanceof ToolApprovalTarget)
                .filter(record -> record.state() == InteractionState.RESPONDED)
                .sorted(Comparator.comparing(record -> record.request().createdAt()))
                .map(record -> new ResolvedInteraction(
                        record.request(), resolved.get(record.request().id())))
                .findFirst();
    }

    @Override
    public synchronized void markResolutionApplied(InteractionRequestId requestId) {
        InteractionRecord current = requireRecord(requestId);
        if (current.state() == InteractionState.APPLIED) return;
        if (current.state() != InteractionState.RESPONDED) {
            throw new IllegalArgumentException("interaction is not resolved");
        }
        records.put(
                requestId,
                new InteractionRecord(
                        current.request(),
                        current.revision() + 1,
                        InteractionState.APPLIED,
                        current.responseId(),
                        current.action(),
                        current.reasonCode(),
                        current.changedAt()));
    }

    @Override
    public synchronized InteractionResolution respond(
            InteractionResponse response, RuntimeCallerContext caller, Instant receivedAt) {
        InteractionResponse existing = responses.get(response.responseId().value());
        if (existing != null) {
            if (!existing.equals(response)) throw new IllegalStateException("response id is already used");
            return new InteractionResolution(requireRequest(response.requestId()), false);
        }
        InteractionRequest request = requireRequest(response.requestId());
        validateLegacyCallerAndRequest(request, response.runId(), caller, receivedAt);
        InteractionRecord current = requireRecord(request.id());
        if (current.state() != InteractionState.PENDING) {
            throw new IllegalStateException("interaction already has a response");
        }
        if (request.approval() && response.type() == InteractionResponseType.CLARIFY) {
            throw new IllegalArgumentException("approval interaction requires approve or reject");
        }
        if (!request.approval() && response.type() == InteractionResponseType.APPROVE) {
            throw new IllegalArgumentException("clarification interaction cannot be approved");
        }
        responses.put(response.responseId().value(), response);
        resolved.put(request.id(), response);
        records.put(
                request.id(),
                new InteractionRecord(
                        request,
                        current.revision() + 1,
                        InteractionState.RESPONDED,
                        Optional.of(response.responseId()),
                        Optional.of(toAction(response.type())),
                        Optional.empty(),
                        Optional.of(receivedAt)));
        return new InteractionResolution(request, true);
    }

    @Override
    public synchronized InteractionSubmissionResolution respond(
            InteractionResponseSubmission response, RuntimeCallerContext caller, Instant receivedAt) {
        String idempotencyScope =
                callerScope(caller) + "|" + response.requestId().value() + "|" + response.idempotencyKey();
        String requestDigest = CanonicalRequestDigest.interactionResponse(response);
        String existingDigest = submissionDigests.get(idempotencyScope);
        if (existingDigest != null) {
            if (!existingDigest.equals(requestDigest)) {
                throw new RuntimeContractException(
                        RuntimeErrorCode.IDEMPOTENCY_CONFLICT,
                        "The idempotency key is already bound to a different interaction response");
            }
            return new InteractionSubmissionResolution(requireRecord(response.requestId()), false);
        }
        InteractionRequest request = requireRequest(response.requestId());
        validateCallerAndRequest(request, response.runId(), caller, receivedAt);
        InteractionRecord current = requireRecord(request.id());
        if (current.revision() != response.expectedRevision()) {
            throw new RuntimeContractException(
                    RuntimeErrorCode.INTERACTION_REVISION_CONFLICT, "The interaction revision is no longer current");
        }
        if (current.state() != InteractionState.PENDING) {
            throw new RuntimeContractException(
                    RuntimeErrorCode.INTERACTION_ALREADY_RESOLVED, "The interaction is already resolved");
        }
        validateActionAndInput(request, response);
        InteractionResponse legacy = new InteractionResponse(
                response.responseId(),
                response.requestId(),
                response.runId(),
                toLegacyType(response.action()),
                response.inputs(),
                response.idempotencyKey(),
                response.respondedAt());
        responses.put(legacy.responseId().value(), legacy);
        resolved.put(request.id(), legacy);
        InteractionRecord recorded = new InteractionRecord(
                request,
                current.revision() + 1,
                InteractionState.RESPONDED,
                Optional.of(response.responseId()),
                Optional.of(response.action()),
                Optional.empty(),
                Optional.of(receivedAt));
        records.put(request.id(), recorded);
        submissionDigests.put(idempotencyScope, requestDigest);
        return new InteractionSubmissionResolution(recorded, true);
    }

    @Override
    public synchronized List<InteractionRecord> due(AgentRunId runId, Instant at, int limit) {
        if (limit < 1 || limit > 1_000) throw new IllegalArgumentException("limit must be in 1..1000");
        return records.values().stream()
                .filter(record -> record.request().runId().equals(runId))
                .filter(record -> record.state() == InteractionState.PENDING)
                .filter(record -> !at.isBefore(record.request().expiresAt()))
                .sorted(Comparator.comparing(record -> record.request().expiresAt()))
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized InteractionRecord expire(InteractionRequestId requestId, long expectedRevision, Instant at) {
        InteractionRecord current = requireRecord(requestId);
        if (current.revision() != expectedRevision) {
            throw new RuntimeContractException(
                    RuntimeErrorCode.INTERACTION_REVISION_CONFLICT, "The interaction revision is no longer current");
        }
        if (current.state() != InteractionState.PENDING) return current;
        if (at.isBefore(current.request().expiresAt())) {
            throw new IllegalArgumentException("interaction has not expired");
        }
        return replaceState(current, InteractionState.EXPIRED, "INTERACTION_EXPIRED", at);
    }

    @Override
    public synchronized InteractionRecord cancel(
            InteractionRequestId requestId, long expectedRevision, String reasonCode, Instant at) {
        return transitionPending(requestId, expectedRevision, InteractionState.CANCELLED, reasonCode, at);
    }

    @Override
    public synchronized InteractionRecord invalidate(
            InteractionRequestId requestId, long expectedRevision, String reasonCode, Instant at) {
        InteractionRecord current = requireRecord(requestId);
        if (current.revision() != expectedRevision) {
            throw new RuntimeContractException(
                    RuntimeErrorCode.INTERACTION_REVISION_CONFLICT, "The interaction revision is no longer current");
        }
        if (current.state() != InteractionState.PENDING && current.state() != InteractionState.RESPONDED) {
            return current;
        }
        return replaceState(current, InteractionState.INVALIDATED, requireReason(reasonCode), at);
    }

    private InteractionRecord transitionPending(
            InteractionRequestId requestId,
            long expectedRevision,
            InteractionState target,
            String reasonCode,
            Instant at) {
        InteractionRecord current = requireRecord(requestId);
        if (current.revision() != expectedRevision) {
            throw new RuntimeContractException(
                    RuntimeErrorCode.INTERACTION_REVISION_CONFLICT, "The interaction revision is no longer current");
        }
        if (current.state() != InteractionState.PENDING) return current;
        return replaceState(current, target, requireReason(reasonCode), at);
    }

    private InteractionRecord replaceState(
            InteractionRecord current, InteractionState target, String reasonCode, Instant at) {
        InteractionRecord changed = new InteractionRecord(
                current.request(),
                current.revision() + 1,
                target,
                current.responseId(),
                current.action(),
                Optional.of(reasonCode),
                Optional.of(at));
        records.put(current.request().id(), changed);
        return changed;
    }

    private static void validateActionAndInput(InteractionRequest request, InteractionResponseSubmission response) {
        InteractionAction action = response.action();
        if (!InteractionSemantics.allowedActions(InteractionSemantics.kind(request))
                .contains(action)) {
            throw new RuntimeContractException(
                    RuntimeErrorCode.INTERACTION_ACTION_NOT_ALLOWED, "The action is not allowed for this interaction");
        }
        if (action.equals(InteractionAction.SUBMIT) && response.inputs().isEmpty()) {
            throw new IllegalArgumentException("the selected action requires bounded response content");
        }
        if (!action.equals(InteractionAction.SUBMIT) && !response.inputs().isEmpty()) {
            throw new IllegalArgumentException("the selected action does not accept response content");
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
        String normalized = java.util.Objects.requireNonNull(reasonCode, "reasonCode must not be null")
                .trim();
        if (normalized.isEmpty() || normalized.length() > 128 || !normalized.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException("reasonCode must be a bounded upper-snake token");
        }
        return normalized;
    }

    private void validateCallerAndRequest(
            InteractionRequest request, AgentRunId responseRunId, RuntimeCallerContext caller, Instant receivedAt) {
        if (!request.runId().equals(responseRunId)) {
            throw new RuntimeContractException(
                    RuntimeErrorCode.INTERACTION_NOT_FOUND, "The interaction does not exist or is not visible");
        }
        if (!request.tenant().equals(caller.tenant())
                || (request.approvalContext().isEmpty() && !request.requester().equals(caller.principal()))) {
            throw new RuntimeContractException(
                    RuntimeErrorCode.INTERACTION_NOT_FOUND, "The interaction does not exist or is not visible");
        }
        if (!receivedAt.isBefore(request.expiresAt())) {
            throw new RuntimeContractException(RuntimeErrorCode.INTERACTION_EXPIRED, "The interaction has expired");
        }
    }

    private static void validateLegacyCallerAndRequest(
            InteractionRequest request, AgentRunId responseRunId, RuntimeCallerContext caller, Instant receivedAt) {
        if (!request.runId().equals(responseRunId)) {
            throw new IllegalArgumentException("response run does not match interaction");
        }
        if (!request.tenant().equals(caller.tenant())
                || (request.approvalContext().isEmpty() && !request.requester().equals(caller.principal()))) {
            throw new SecurityException("caller is not allowed to respond to interaction");
        }
        if (!receivedAt.isBefore(request.expiresAt())) {
            throw new IllegalStateException("interaction expired");
        }
    }

    private InteractionRecord requireRecord(InteractionRequestId id) {
        InteractionRecord record = records.get(id);
        if (record == null) {
            throw new RuntimeContractException(
                    RuntimeErrorCode.INTERACTION_NOT_FOUND, "The interaction does not exist or is not visible");
        }
        return record;
    }

    private InteractionRequest requireRequest(InteractionRequestId id) {
        InteractionRequest request = requests.get(id);
        if (request == null) {
            throw new RuntimeContractException(
                    RuntimeErrorCode.INTERACTION_NOT_FOUND, "The interaction does not exist or is not visible");
        }
        return request;
    }
}

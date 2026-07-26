package io.haifa.agent.runtime.core.interaction;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.policy.api.ApprovalVerification;
import io.haifa.agent.runtime.api.InteractionRequestId;
import io.haifa.agent.runtime.api.InteractionResponse;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseSubmission;
import io.haifa.agent.runtime.core.bootstrap.RuntimeCallerContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface InteractionPort {
    void create(InteractionRequest request);

    Optional<InteractionRequest> pending(AgentRunId runId);

    Optional<InteractionRequest> find(InteractionRequestId requestId);

    default Optional<InteractionRecord> record(InteractionRequestId requestId) {
        return find(requestId).map(InteractionRecord::pending);
    }

    default Optional<InteractionRecord> pendingRecord(AgentRunId runId) {
        return pending(runId).map(InteractionRecord::pending);
    }

    Optional<ResolvedInteraction> unappliedToolResolution(AgentRunId runId);

    void markResolutionApplied(InteractionRequestId requestId);

    InteractionResolution respond(InteractionResponse response, RuntimeCallerContext caller, Instant receivedAt);

    default InteractionSubmissionResolution respond(
            InteractionResponseSubmission response, RuntimeCallerContext caller, Instant receivedAt) {
        throw new UnsupportedOperationException("revision-aware interaction responses are not supported");
    }

    default List<InteractionRecord> due(AgentRunId runId, Instant at, int limit) {
        return List.of();
    }

    default InteractionRecord expire(InteractionRequestId requestId, long expectedRevision, Instant at) {
        throw new UnsupportedOperationException("interaction expiration is not supported");
    }

    default InteractionRecord cancel(
            InteractionRequestId requestId, long expectedRevision, String reasonCode, Instant at) {
        throw new UnsupportedOperationException("interaction cancellation is not supported");
    }

    default InteractionRecord invalidate(
            InteractionRequestId requestId, long expectedRevision, String reasonCode, Instant at) {
        throw new UnsupportedOperationException("interaction invalidation is not supported");
    }

    default void recordApprovalVerification(InteractionResponseId responseId, ApprovalVerification verification) {}
}

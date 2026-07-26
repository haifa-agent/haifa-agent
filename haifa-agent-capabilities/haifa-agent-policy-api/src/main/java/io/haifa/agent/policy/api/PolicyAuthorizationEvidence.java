package io.haifa.agent.policy.api;

import java.time.Instant;
import java.util.Objects;

/** Trusted evidence that one ASK challenge was satisfied for its exact decision. */
public record PolicyAuthorizationEvidence(
        PolicyDecisionId decisionId,
        String requestDigest,
        ApprovalRequester requester,
        ApprovalResponder responder,
        Instant approvedAt,
        Instant expiresAt) {
    public PolicyAuthorizationEvidence {
        decisionId = Objects.requireNonNull(decisionId, "decisionId must not be null");
        requestDigest = PolicyValues.requireIdentifier(requestDigest, "requestDigest");
        requester = Objects.requireNonNull(requester, "requester must not be null");
        responder = Objects.requireNonNull(responder, "responder must not be null");
        approvedAt = Objects.requireNonNull(approvedAt, "approvedAt must not be null");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (!expiresAt.isAfter(approvedAt)) {
            throw new IllegalArgumentException("expiresAt must be after approvedAt");
        }
    }

    public boolean validAt(Instant instant) {
        return !instant.isBefore(approvedAt) && instant.isBefore(expiresAt);
    }
}

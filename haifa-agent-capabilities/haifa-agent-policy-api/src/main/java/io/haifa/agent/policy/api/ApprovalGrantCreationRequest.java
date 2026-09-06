package io.haifa.agent.policy.api;

import java.time.Instant;
import java.util.Objects;

/** Verified source facts required to create one capability-confirmation grant. */
public record ApprovalGrantCreationRequest(
        PolicyDecision decision,
        ApprovalRequestContext approvalRequest,
        ApprovalVerification verification,
        String approvalRequestRef,
        String approvalResponseRef,
        ApprovalResponder responder,
        ApprovalReuseScope reuseScope,
        Instant expiresAt) {
    public ApprovalGrantCreationRequest {
        decision = Objects.requireNonNull(decision, "decision must not be null");
        approvalRequest = Objects.requireNonNull(approvalRequest, "approvalRequest must not be null");
        verification = Objects.requireNonNull(verification, "verification must not be null");
        approvalRequestRef = PolicyValues.requireIdentifier(approvalRequestRef, "approvalRequestRef");
        approvalResponseRef = PolicyValues.requireIdentifier(approvalResponseRef, "approvalResponseRef");
        responder = Objects.requireNonNull(responder, "responder must not be null");
        reuseScope = Objects.requireNonNull(reuseScope, "reuseScope must not be null");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        PolicyRequest request = decision.request()
                .orElseThrow(() -> new IllegalArgumentException("grant creation requires a bound policy decision"));
        if (decision.effect() != PolicyEffect.ASK || decision.challenge().isEmpty()) {
            throw new IllegalArgumentException("grant creation requires an ASK decision");
        }
        if (!decision.id().equals(approvalRequest.decisionId())) {
            throw new IllegalArgumentException("approval request does not reference the policy decision");
        }
        if (approvalRequest.semantics() != ApprovalSemantics.CAPABILITY_CONFIRMATION) {
            throw new IllegalArgumentException("business authorization cannot create a reusable grant");
        }
        if (!verification.accepted()) {
            throw new IllegalArgumentException("unverified approval cannot create a grant");
        }
        if (!approvalRequest.allowedReuseScopes().contains(reuseScope)) {
            throw new IllegalArgumentException("reuseScope was not offered by the approval request");
        }
        if (!approvalRequest.requester().tenant().equals(request.subject().tenant())
                || !approvalRequest
                        .requester()
                        .principal()
                        .equals(request.subject().principal())) {
            throw new IllegalArgumentException("approval requester does not match policy subject");
        }
        if (!expiresAt.isAfter(approvalRequest.createdAt())) {
            throw new IllegalArgumentException("grant expiresAt must be after approval creation");
        }
        if (approvalRequest.expiresAt().isPresent()
                && expiresAt.isAfter(approvalRequest.expiresAt().orElseThrow())) {
            throw new IllegalArgumentException("grant cannot outlive the approval request");
        }
        if (reuseScope == ApprovalReuseScope.SESSION
                && request.context().sessionRef().isEmpty()) {
            throw new IllegalArgumentException("SESSION grant requires policy sessionRef");
        }
        if (reuseScope == ApprovalReuseScope.PROJECT
                && (request.context().projectRef().isEmpty()
                        || request.context().projectTrustRef().isEmpty()
                        || request.context().securityConfigurationDigest().isEmpty())) {
            throw new IllegalArgumentException("PROJECT grant requires policy project trust context");
        }
    }
}

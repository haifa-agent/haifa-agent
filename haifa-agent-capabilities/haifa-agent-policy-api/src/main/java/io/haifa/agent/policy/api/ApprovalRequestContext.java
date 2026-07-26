package io.haifa.agent.policy.api;

import static io.haifa.agent.policy.api.PolicyValues.optionalIdentifier;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record ApprovalRequestContext(
        PolicyDecisionId decisionId,
        ApprovalSemantics semantics,
        Set<ApprovalReuseScope> allowedReuseScopes,
        ApprovalRequester requester,
        ApprovalTargetRef target,
        Optional<ApprovalAuthorityRequirementRef> authorityRequirement,
        Instant createdAt,
        Instant expiresAt,
        Optional<String> externalCorrelationRef) {
    public ApprovalRequestContext {
        decisionId = Objects.requireNonNull(decisionId, "decisionId must not be null");
        semantics = Objects.requireNonNull(semantics, "semantics must not be null");
        allowedReuseScopes =
                Set.copyOf(Objects.requireNonNull(allowedReuseScopes, "allowedReuseScopes must not be null"));
        requester = Objects.requireNonNull(requester, "requester must not be null");
        target = Objects.requireNonNull(target, "target must not be null");
        authorityRequirement = Objects.requireNonNull(authorityRequirement, "authorityRequirement must not be null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        externalCorrelationRef = optionalIdentifier(externalCorrelationRef, "externalCorrelationRef");
        if (allowedReuseScopes.isEmpty()) {
            throw new IllegalArgumentException("allowedReuseScopes must not be empty");
        }
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
        if (semantics == ApprovalSemantics.BUSINESS_AUTHORIZATION) {
            if (!allowedReuseScopes.equals(Set.of(ApprovalReuseScope.ONCE))) {
                throw new IllegalArgumentException("business authorization only allows ONCE");
            }
            if (authorityRequirement.isEmpty()) {
                throw new IllegalArgumentException("business authorization requires authority");
            }
        }
    }
}

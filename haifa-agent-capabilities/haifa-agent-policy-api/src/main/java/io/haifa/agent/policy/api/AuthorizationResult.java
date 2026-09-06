package io.haifa.agent.policy.api;

import java.util.Objects;
import java.util.Optional;

/** Safe, product-neutral authorization projection without host paths, credentials, or raw requests. */
public record AuthorizationResult(
        AuthorizationClassification classification,
        AuthorizationSource source,
        String reasonCode,
        String safeExplanation,
        Optional<PolicyDecisionId> decisionId,
        Optional<ApprovalGrantId> grantId) {
    public AuthorizationResult {
        classification = Objects.requireNonNull(classification, "classification must not be null");
        source = Objects.requireNonNull(source, "source must not be null");
        reasonCode = PolicyValues.requireIdentifier(reasonCode, "reasonCode");
        safeExplanation = PolicyValues.requireSafeText(safeExplanation, "safeExplanation");
        decisionId = Objects.requireNonNull(decisionId, "decisionId must not be null");
        grantId = Objects.requireNonNull(grantId, "grantId must not be null");
        validateShape(classification, source, decisionId, grantId);
    }

    public static AuthorizationResult fromPolicy(PolicyDecision decision) {
        Objects.requireNonNull(decision, "decision must not be null");
        AuthorizationClassification classification =
                switch (decision.effect()) {
                    case DENY -> AuthorizationClassification.HARD_DENY;
                    case ASK -> AuthorizationClassification.REQUIRES_APPROVAL;
                    case ALLOW -> AuthorizationClassification.ALLOW;
                };
        return new AuthorizationResult(
                classification,
                AuthorizationSource.POLICY,
                decision.reasonCode(),
                decision.safeExplanation(),
                Optional.of(decision.id()),
                Optional.empty());
    }

    public static AuthorizationResult fromGrant(PolicyDecision decision, ApprovalGrant grant) {
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(grant, "grant must not be null");
        return new AuthorizationResult(
                AuthorizationClassification.ALLOW,
                AuthorizationSource.GRANT,
                "APPROVAL_GRANT_AUTHORIZED",
                "A current scoped approval grant authorized the exact request",
                Optional.of(decision.id()),
                Optional.of(grant.id()));
    }

    public static AuthorizationResult protocolError(String reasonCode, String safeExplanation) {
        return new AuthorizationResult(
                AuthorizationClassification.PROTOCOL_ERROR,
                AuthorizationSource.PROTOCOL,
                reasonCode,
                safeExplanation,
                Optional.empty(),
                Optional.empty());
    }

    public static AuthorizationResult executionOutcome(String reasonCode, String safeExplanation) {
        return new AuthorizationResult(
                AuthorizationClassification.EXECUTION_OUTCOME,
                AuthorizationSource.EXECUTION,
                reasonCode,
                safeExplanation,
                Optional.empty(),
                Optional.empty());
    }

    public boolean authorized() {
        return classification == AuthorizationClassification.ALLOW;
    }

    public boolean approvable() {
        return classification == AuthorizationClassification.REQUIRES_APPROVAL;
    }

    private static void validateShape(
            AuthorizationClassification classification,
            AuthorizationSource source,
            Optional<PolicyDecisionId> decisionId,
            Optional<ApprovalGrantId> grantId) {
        if (classification == AuthorizationClassification.PROTOCOL_ERROR) {
            requireSource(source, AuthorizationSource.PROTOCOL, classification);
        } else if (classification == AuthorizationClassification.EXECUTION_OUTCOME) {
            requireSource(source, AuthorizationSource.EXECUTION, classification);
        } else if (source != AuthorizationSource.POLICY && source != AuthorizationSource.GRANT) {
            throw new IllegalArgumentException("authorization classification requires policy or grant source");
        }
        if ((source == AuthorizationSource.POLICY || source == AuthorizationSource.GRANT) && decisionId.isEmpty()) {
            throw new IllegalArgumentException("policy and grant results require decisionId");
        }
        if (source == AuthorizationSource.GRANT
                && (classification != AuthorizationClassification.ALLOW || grantId.isEmpty())) {
            throw new IllegalArgumentException("grant source requires ALLOW and grantId");
        }
        if (source != AuthorizationSource.GRANT && grantId.isPresent()) {
            throw new IllegalArgumentException("only grant source may carry grantId");
        }
        if ((classification == AuthorizationClassification.PROTOCOL_ERROR
                        || classification == AuthorizationClassification.EXECUTION_OUTCOME)
                && (decisionId.isPresent() || grantId.isPresent())) {
            throw new IllegalArgumentException("non-authorization results cannot carry decision or grant references");
        }
    }

    private static void requireSource(
            AuthorizationSource actual, AuthorizationSource expected, AuthorizationClassification classification) {
        if (actual != expected) {
            throw new IllegalArgumentException(classification + " requires " + expected + " source");
        }
    }
}

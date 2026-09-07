package io.haifa.agent.policy.core;

import io.haifa.agent.policy.api.ApprovalAuthorityDecision;
import io.haifa.agent.policy.api.ApprovalAuthorityRequirementRef;
import io.haifa.agent.policy.api.ApprovalAuthorityVerifier;
import io.haifa.agent.policy.api.ApprovalRequester;
import io.haifa.agent.policy.api.ApprovalResponder;
import io.haifa.agent.policy.api.ApprovalTargetRef;
import io.haifa.agent.policy.api.ApprovalTargetValidation;
import io.haifa.agent.policy.api.ApprovalTargetValidator;
import io.haifa.agent.policy.api.ApprovalVerification;
import io.haifa.agent.policy.api.ApprovalVerificationService;
import java.util.Map;
import java.util.Objects;

public final class DefaultApprovalVerificationService implements ApprovalVerificationService {
    private static final ApprovalAuthorityRequirementRef LOCAL_REQUIREMENT =
            new ApprovalAuthorityRequirementRef(LocalCapabilityAuthorityVerifier.PROVIDER_ID, "requester", "1");

    private final ApprovalAuthorityVerifier localVerifier;
    private final Map<String, ApprovalTargetValidator> targetValidators;

    public DefaultApprovalVerificationService(
            ApprovalAuthorityVerifier localVerifier,
            Map<String, ApprovalTargetValidator> targetValidators) {
        this.localVerifier = Objects.requireNonNull(localVerifier, "localVerifier must not be null");
        this.targetValidators =
                Map.copyOf(Objects.requireNonNull(targetValidators, "targetValidators must not be null"));
    }

    @Override
    public ApprovalVerification verify(
            ApprovalRequester requester, ApprovalTargetRef target, ApprovalResponder responder) {
        Objects.requireNonNull(requester, "requester must not be null");
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(responder, "responder must not be null");
        ApprovalTargetValidator validator = targetValidators.get(target.targetType());
        if (validator == null) return new ApprovalVerification(false, "TARGET_VALIDATOR_UNAVAILABLE");
        ApprovalTargetValidation current;
        try {
            current = validator.validateCurrent(target);
        } catch (RuntimeException exception) {
            return new ApprovalVerification(false, "TARGET_VALIDATION_FAILED");
        }
        if (current == null || !current.current()) {
            return new ApprovalVerification(
                    false, current == null ? "TARGET_VALIDATOR_RETURNED_NULL" : current.reasonCode());
        }
        ApprovalAuthorityDecision authority = localVerifier.verify(LOCAL_REQUIREMENT, requester, responder, target);
        return authority != null && authority.eligible()
                ? new ApprovalVerification(true, "APPROVAL_VERIFIED")
                : new ApprovalVerification(
                        false, authority == null ? "AUTHORITY_VERIFIER_RETURNED_NULL" : authority.reasonCode());
    }

}

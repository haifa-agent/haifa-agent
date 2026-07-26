package io.haifa.agent.policy.core;

import io.haifa.agent.policy.api.ApprovalAuthorityDecision;
import io.haifa.agent.policy.api.ApprovalAuthorityRequirementRef;
import io.haifa.agent.policy.api.ApprovalAuthorityVerifier;
import io.haifa.agent.policy.api.ApprovalRequestContext;
import io.haifa.agent.policy.api.ApprovalResponder;
import io.haifa.agent.policy.api.ApprovalSemantics;
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
    private final Map<String, ApprovalAuthorityVerifier> authorityVerifiers;
    private final Map<String, ApprovalTargetValidator> targetValidators;

    public DefaultApprovalVerificationService(
            ApprovalAuthorityVerifier localVerifier,
            Map<String, ApprovalAuthorityVerifier> authorityVerifiers,
            Map<String, ApprovalTargetValidator> targetValidators) {
        this.localVerifier = Objects.requireNonNull(localVerifier, "localVerifier must not be null");
        this.authorityVerifiers =
                Map.copyOf(Objects.requireNonNull(authorityVerifiers, "authorityVerifiers must not be null"));
        this.targetValidators =
                Map.copyOf(Objects.requireNonNull(targetValidators, "targetValidators must not be null"));
    }

    @Override
    public ApprovalVerification verify(ApprovalRequestContext request, ApprovalResponder responder) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(responder, "responder must not be null");
        ApprovalTargetValidator validator =
                targetValidators.get(request.target().targetType());
        if (validator == null) return new ApprovalVerification(false, "TARGET_VALIDATOR_UNAVAILABLE");
        ApprovalTargetValidation target = safelyValidate(validator, request);
        if (!target.current()) return new ApprovalVerification(false, target.reasonCode());

        ApprovalAuthorityDecision authority;
        if (request.semantics() == ApprovalSemantics.CAPABILITY_CONFIRMATION
                && request.authorityRequirement().isEmpty()) {
            authority = safelyVerify(localVerifier, LOCAL_REQUIREMENT, request, responder);
        } else {
            ApprovalAuthorityRequirementRef requirement =
                    request.authorityRequirement().orElse(null);
            if (requirement == null) return new ApprovalVerification(false, "AUTHORITY_REQUIREMENT_MISSING");
            ApprovalAuthorityVerifier verifier = authorityVerifiers.get(requirement.providerId());
            if (verifier == null) return new ApprovalVerification(false, "AUTHORITY_VERIFIER_UNAVAILABLE");
            authority = safelyVerify(verifier, requirement, request, responder);
        }
        return authority.eligible()
                ? new ApprovalVerification(true, "APPROVAL_VERIFIED")
                : new ApprovalVerification(false, authority.reasonCode());
    }

    private static ApprovalTargetValidation safelyValidate(
            ApprovalTargetValidator validator, ApprovalRequestContext request) {
        try {
            ApprovalTargetValidation result = validator.validateCurrent(request.target());
            return result == null
                    ? new ApprovalTargetValidation(
                            io.haifa.agent.policy.api.ApprovalTargetStatus.UNAVAILABLE,
                            "TARGET_VALIDATOR_RETURNED_NULL")
                    : result;
        } catch (RuntimeException exception) {
            return new ApprovalTargetValidation(
                    io.haifa.agent.policy.api.ApprovalTargetStatus.UNAVAILABLE, "TARGET_VALIDATION_FAILED");
        }
    }

    private static ApprovalAuthorityDecision safelyVerify(
            ApprovalAuthorityVerifier verifier,
            ApprovalAuthorityRequirementRef requirement,
            ApprovalRequestContext request,
            ApprovalResponder responder) {
        try {
            ApprovalAuthorityDecision result =
                    verifier.verify(requirement, request.requester(), responder, request.target());
            return result == null
                    ? new ApprovalAuthorityDecision(
                            io.haifa.agent.policy.api.ApprovalAuthorityStatus.UNAVAILABLE,
                            "AUTHORITY_VERIFIER_RETURNED_NULL")
                    : result;
        } catch (RuntimeException exception) {
            return new ApprovalAuthorityDecision(
                    io.haifa.agent.policy.api.ApprovalAuthorityStatus.UNAVAILABLE, "AUTHORITY_VERIFICATION_FAILED");
        }
    }
}

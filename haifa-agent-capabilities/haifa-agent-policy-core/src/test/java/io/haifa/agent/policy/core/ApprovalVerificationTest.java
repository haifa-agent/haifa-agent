package io.haifa.agent.policy.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.policy.api.ApprovalRequester;
import io.haifa.agent.policy.api.ApprovalResponder;
import io.haifa.agent.policy.api.ApprovalTargetRef;
import io.haifa.agent.policy.api.ApprovalTargetStatus;
import io.haifa.agent.policy.api.ApprovalTargetValidation;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApprovalVerificationTest {
    private static final TenantRef TENANT = new TenantRef("tenant");
    private static final PrincipalRef REQUESTER = new PrincipalRef("requester", "user");
    private static final ApprovalRequester APPROVAL_REQUESTER = new ApprovalRequester(TENANT, REQUESTER);
    private static final ApprovalTargetRef TARGET =
            new ApprovalTargetRef("tool-call", "call", "1", "write", "sha256:args", "Write file");

    @Test
    void localApprovalRequiresSameTenantAndPrincipal() {
        DefaultApprovalVerificationService service = service(currentTarget());

        assertThat(service.verify(APPROVAL_REQUESTER, TARGET, new ApprovalResponder(TENANT, REQUESTER))
                        .accepted())
                .isTrue();
        assertThat(service.verify(
                                APPROVAL_REQUESTER,
                                TARGET,
                                new ApprovalResponder(TENANT, new PrincipalRef("other", "user")))
                        .accepted())
                .isFalse();
        assertThat(service.verify(APPROVAL_REQUESTER, TARGET, new ApprovalResponder(new TenantRef("other"), REQUESTER))
                        .accepted())
                .isFalse();
    }

    @Test
    void missingFailingOrStaleTargetValidatorFailsClosed() {
        assertThat(service(Map.of())
                        .verify(APPROVAL_REQUESTER, TARGET, new ApprovalResponder(TENANT, REQUESTER))
                        .reasonCode())
                .isEqualTo("TARGET_VALIDATOR_UNAVAILABLE");
        assertThat(service(Map.of("tool-call", target -> {
                            throw new IllegalStateException("unsafe detail");
                        }))
                        .verify(APPROVAL_REQUESTER, TARGET, new ApprovalResponder(TENANT, REQUESTER))
                        .reasonCode())
                .isEqualTo("TARGET_VALIDATION_FAILED");
        assertThat(service(Map.of(
                                "tool-call",
                                target -> new ApprovalTargetValidation(ApprovalTargetStatus.STALE, "TARGET_STALE")))
                        .verify(APPROVAL_REQUESTER, TARGET, new ApprovalResponder(TENANT, REQUESTER))
                        .reasonCode())
                .isEqualTo("TARGET_STALE");
    }

    private static DefaultApprovalVerificationService service(
            Map<String, io.haifa.agent.policy.api.ApprovalTargetValidator> targets) {
        return new DefaultApprovalVerificationService(new LocalCapabilityAuthorityVerifier(), targets);
    }

    private static Map<String, io.haifa.agent.policy.api.ApprovalTargetValidator> currentTarget() {
        return Map.of(
                "tool-call", target -> new ApprovalTargetValidation(ApprovalTargetStatus.CURRENT, "TARGET_CURRENT"));
    }
}

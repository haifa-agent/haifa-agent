package io.haifa.agent.policy.api;

import java.time.Instant;
import java.util.Objects;

/** Safe result of one reason-aware optimistic grant revocation. */
public record ApprovalGrantRevocation(ApprovalGrantId grantId, String reasonCode, Instant revokedAt, long version) {
    public ApprovalGrantRevocation {
        grantId = Objects.requireNonNull(grantId, "grantId must not be null");
        reasonCode = PolicyValues.requireIdentifier(reasonCode, "reasonCode");
        revokedAt = Objects.requireNonNull(revokedAt, "revokedAt must not be null");
        if (version < 1) throw new IllegalArgumentException("revoked grant version must be positive");
    }
}

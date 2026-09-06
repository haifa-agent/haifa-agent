package io.haifa.agent.policy.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ApprovalGrantStore {
    void save(ApprovalGrant grant);

    Optional<ApprovalGrant> find(ApprovalGrantId id);

    List<ApprovalGrant> findCandidates(ApprovalGrantQuery query);

    ApprovalGrant consumeOnce(ApprovalGrantId id, long expectedVersion, Instant consumedAt);

    default ApprovalGrant revoke(ApprovalGrantId id, long expectedVersion, Instant revokedAt) {
        return revoke(id, expectedVersion, revokedAt, "GRANT_REVOKED");
    }

    ApprovalGrant revoke(ApprovalGrantId id, long expectedVersion, Instant revokedAt, String reasonCode);
}

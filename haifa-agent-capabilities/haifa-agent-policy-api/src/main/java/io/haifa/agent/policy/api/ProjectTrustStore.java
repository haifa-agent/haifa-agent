package io.haifa.agent.policy.api;

import java.time.Instant;
import java.util.Optional;

public interface ProjectTrustStore {
    void save(ProjectTrust trust);

    Optional<ProjectTrust> find(ProjectTrustRef ref);

    ProjectTrust revoke(ProjectTrustRef ref, long expectedVersion, Instant revokedAt);
}

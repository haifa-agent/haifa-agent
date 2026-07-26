package io.haifa.agent.policy.core;

import io.haifa.agent.policy.api.ApprovalGrant;
import io.haifa.agent.policy.api.ApprovalGrantQuery;
import io.haifa.agent.policy.api.ApprovalGrantStore;
import io.haifa.agent.policy.api.ApprovalReuseScope;
import io.haifa.agent.policy.api.ProjectTrustExpectation;
import io.haifa.agent.policy.api.ProjectTrustStore;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves exact capability-confirmation grants. Business authorization never enters this service
 * because {@link ApprovalGrant} rejects that semantic at construction time.
 */
public final class DefaultApprovalGrantService {
    private final ApprovalGrantStore grants;
    private final ProjectTrustStore trusts;
    private final ApprovalGrantMatcher matcher;
    private final Clock clock;

    public DefaultApprovalGrantService(
            ApprovalGrantStore grants, ProjectTrustStore trusts, ApprovalGrantMatcher matcher, Clock clock) {
        this.grants = Objects.requireNonNull(grants, "grants must not be null");
        this.trusts = Objects.requireNonNull(trusts, "trusts must not be null");
        this.matcher = Objects.requireNonNull(matcher, "matcher must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public void create(ApprovalGrant grant) {
        grants.save(Objects.requireNonNull(grant, "grant must not be null"));
    }

    /**
     * Returns and, for ONCE, atomically consumes the first exact grant. PROJECT grants fail closed
     * unless the caller supplies the current product-owned identity/configuration values and the
     * referenced trust still matches them.
     */
    public Optional<ApprovalGrant> authorize(
            ApprovalGrantQuery query, Optional<ProjectTrustExpectation> projectExpectation) {
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(projectExpectation, "projectExpectation must not be null");
        Instant now = Instant.ofEpochMilli(clock.millis());
        for (ApprovalGrant grant : grants.findCandidates(query)) {
            if (!matcher.matches(grant, query, now) || !validProjectTrust(grant, projectExpectation, now)) {
                continue;
            }
            if (grant.reuseScope() != ApprovalReuseScope.ONCE) {
                return Optional.of(grant);
            }
            try {
                return Optional.of(grants.consumeOnce(grant.id(), grant.version(), now));
            } catch (IllegalStateException conflict) {
                // Another actor consumed or revoked this exact grant after the candidate read.
            }
        }
        return Optional.empty();
    }

    private boolean validProjectTrust(
            ApprovalGrant grant, Optional<ProjectTrustExpectation> projectExpectation, Instant now) {
        if (grant.reuseScope() != ApprovalReuseScope.PROJECT) {
            return true;
        }
        if (projectExpectation.isEmpty()) {
            return false;
        }
        ProjectTrustExpectation expected = projectExpectation.orElseThrow();
        return grant.projectTrustRef()
                .flatMap(trusts::find)
                .filter(trust -> trust.matches(
                        expected.tenant(),
                        expected.principal(),
                        expected.projectRef(),
                        expected.canonicalProjectIdentity(),
                        expected.trustedRootIdentity(),
                        expected.securityConfigurationDigest(),
                        expected.productProfileRef(),
                        now))
                .isPresent();
    }
}

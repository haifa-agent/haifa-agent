package io.haifa.agent.policy.core;

import io.haifa.agent.policy.api.ApprovalGrant;
import io.haifa.agent.policy.api.ApprovalGrantQuery;
import io.haifa.agent.policy.api.ApprovalReuseScope;
import java.time.Instant;
import java.util.Objects;

public final class ApprovalGrantMatcher {
    public boolean matches(ApprovalGrant grant, ApprovalGrantQuery query, Instant now) {
        Objects.requireNonNull(grant, "grant must not be null");
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (!grant.activeAt(now)
                || !grant.subject().equals(query.subject())
                || !grant.action().equals(query.action())
                || !grant.target().targetType().equals(query.target().targetType())
                || !grant.target().operation().equals(query.target().operation())
                || !grant.target().targetDigest().equals(query.target().targetDigest())) {
            return false;
        }
        if (grant.reuseScope() == ApprovalReuseScope.ONCE) {
            return grant.target().equals(query.target());
        }
        if (grant.reuseScope() == ApprovalReuseScope.SESSION) {
            return grant.sessionRef().equals(query.context().sessionRef());
        }
        return grant.projectRef().equals(query.context().projectRef())
                && grant.projectTrustRef().equals(query.context().projectTrustRef())
                && grant.securityConfigurationDigest().equals(query.context().securityConfigurationDigest());
    }
}

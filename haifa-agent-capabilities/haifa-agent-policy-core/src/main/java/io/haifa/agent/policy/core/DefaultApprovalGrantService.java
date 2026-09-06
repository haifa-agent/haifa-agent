package io.haifa.agent.policy.core;

import io.haifa.agent.policy.api.ApprovalGrant;
import io.haifa.agent.policy.api.ApprovalGrantCreationRequest;
import io.haifa.agent.policy.api.ApprovalGrantId;
import io.haifa.agent.policy.api.ApprovalGrantIdGenerator;
import io.haifa.agent.policy.api.ApprovalGrantQuery;
import io.haifa.agent.policy.api.ApprovalGrantRevocation;
import io.haifa.agent.policy.api.ApprovalGrantStore;
import io.haifa.agent.policy.api.ApprovalReuseScope;
import io.haifa.agent.policy.api.ApprovalTargetRef;
import io.haifa.agent.policy.api.AuthorizationResult;
import io.haifa.agent.policy.api.PolicyAuthorizationService;
import io.haifa.agent.policy.api.PolicyDecision;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.policy.api.PolicyRequest;
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
public final class DefaultApprovalGrantService implements PolicyAuthorizationService {
    private final ApprovalGrantStore grants;
    private final ProjectTrustStore trusts;
    private final ApprovalGrantMatcher matcher;
    private final Clock clock;
    private final ApprovalGrantIdGenerator ids;

    public DefaultApprovalGrantService(
            ApprovalGrantStore grants, ProjectTrustStore trusts, ApprovalGrantMatcher matcher, Clock clock) {
        this(grants, trusts, matcher, clock, () -> {
            throw new IllegalStateException("approval grant id generator is not configured");
        });
    }

    public DefaultApprovalGrantService(
            ApprovalGrantStore grants,
            ProjectTrustStore trusts,
            ApprovalGrantMatcher matcher,
            Clock clock,
            ApprovalGrantIdGenerator ids) {
        this.grants = Objects.requireNonNull(grants, "grants must not be null");
        this.trusts = Objects.requireNonNull(trusts, "trusts must not be null");
        this.matcher = Objects.requireNonNull(matcher, "matcher must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
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

    @Override
    public AuthorizationResult authorize(
            PolicyDecision decision, ApprovalTargetRef target, Optional<ProjectTrustExpectation> projectExpectation) {
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(projectExpectation, "projectExpectation must not be null");
        if (decision.effect() != PolicyEffect.ASK || decision.request().isEmpty()) {
            return AuthorizationResult.fromPolicy(decision);
        }
        PolicyRequest request = decision.request().orElseThrow();
        return authorize(
                        new ApprovalGrantQuery(request.subject(), request.context(), request.action(), target),
                        projectExpectation)
                .map(grant -> AuthorizationResult.fromGrant(decision, grant))
                .orElseGet(() -> AuthorizationResult.fromPolicy(decision));
    }

    @Override
    public ApprovalGrant createGrant(ApprovalGrantCreationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        Instant now = Instant.ofEpochMilli(clock.millis());
        if (!request.expiresAt().isAfter(now)) {
            throw new IllegalArgumentException("grant expiry must be in the future");
        }
        PolicyRequest policyRequest = request.decision().request().orElseThrow();
        var context = policyRequest.context();
        ApprovalReuseScope scope = request.reuseScope();
        ApprovalGrant grant = new ApprovalGrant(
                requireId(ids.nextId()),
                request.approvalRequest().semantics(),
                scope,
                policyRequest.subject(),
                policyRequest.action(),
                request.approvalRequest().target(),
                scope == ApprovalReuseScope.SESSION ? context.sessionRef() : Optional.empty(),
                scope == ApprovalReuseScope.PROJECT ? context.projectRef() : Optional.empty(),
                scope == ApprovalReuseScope.PROJECT ? context.projectTrustRef() : Optional.empty(),
                scope == ApprovalReuseScope.PROJECT ? context.securityConfigurationDigest() : Optional.empty(),
                request.decision().id(),
                request.approvalRequestRef(),
                request.approvalResponseRef(),
                request.responder(),
                now,
                Optional.of(request.expiresAt()),
                io.haifa.agent.policy.api.ApprovalGrantState.ACTIVE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0);
        grants.save(grant);
        return grant;
    }

    @Override
    public ApprovalGrantRevocation revoke(ApprovalGrantId id, long expectedVersion, String reasonCode) {
        Objects.requireNonNull(id, "id must not be null");
        Instant now = Instant.ofEpochMilli(clock.millis());
        ApprovalGrant revoked = grants.revoke(id, expectedVersion, now, reasonCode);
        return new ApprovalGrantRevocation(
                revoked.id(),
                revoked.revocationReasonCode().orElseThrow(),
                revoked.revokedAt().orElseThrow(),
                revoked.version());
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

    private static ApprovalGrantId requireId(ApprovalGrantId id) {
        return Objects.requireNonNull(id, "approval grant id generator returned null");
    }
}

package io.haifa.agent.store.sqlite;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.policy.api.ApprovalGrant;
import io.haifa.agent.policy.api.ApprovalGrantId;
import io.haifa.agent.policy.api.ApprovalGrantQuery;
import io.haifa.agent.policy.api.ApprovalGrantState;
import io.haifa.agent.policy.api.ApprovalGrantStore;
import io.haifa.agent.policy.api.ApprovalResponder;
import io.haifa.agent.policy.api.ApprovalReuseScope;
import io.haifa.agent.policy.api.ApprovalSemantics;
import io.haifa.agent.policy.api.ApprovalTargetRef;
import io.haifa.agent.policy.api.PolicyAction;
import io.haifa.agent.policy.api.PolicyDecisionId;
import io.haifa.agent.policy.api.PolicySubject;
import io.haifa.agent.policy.api.ProjectTrustRef;
import io.haifa.agent.store.sqlite.mybatis.ApprovalGrantRow;
import io.haifa.agent.store.sqlite.mybatis.PolicyStoreMapper;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class SqliteApprovalGrantStore implements ApprovalGrantStore {
    private final SqliteRuntimeUnitOfWork unitOfWork;

    public SqliteApprovalGrantStore(SqliteRuntimeUnitOfWork unitOfWork) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
    }

    @Override
    public void save(ApprovalGrant grant) {
        Objects.requireNonNull(grant, "grant must not be null");
        SqlitePolicyStoreSupport.execute(unitOfWork, () -> {
            PolicyStoreMapper mapper = unitOfWork.mapper(PolicyStoreMapper.class);
            ApprovalGrantRow existing = mapper.findApprovalGrant(grant.id().value());
            if (existing != null) {
                if (!fromRow(existing).equals(grant)) {
                    throw new IllegalStateException("approval grant id is already used");
                }
                return null;
            }
            mapper.insertApprovalGrant(toRow(grant));
            return null;
        });
    }

    @Override
    public Optional<ApprovalGrant> find(ApprovalGrantId id) {
        Objects.requireNonNull(id, "id must not be null");
        return SqlitePolicyStoreSupport.execute(unitOfWork, () -> Optional.ofNullable(
                        unitOfWork.mapper(PolicyStoreMapper.class).findApprovalGrant(id.value()))
                .map(SqliteApprovalGrantStore::fromRow));
    }

    @Override
    public List<ApprovalGrant> findCandidates(ApprovalGrantQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        return SqlitePolicyStoreSupport.execute(unitOfWork, () -> unitOfWork
                .mapper(PolicyStoreMapper.class)
                .findApprovalGrantCandidates(
                        query.subject().tenant().tenantId(),
                        query.subject().principal().principalId(),
                        query.subject().principal().principalType(),
                        query.subject().productId(),
                        query.action().capability(),
                        query.action().operation(),
                        query.target().targetType())
                .stream()
                .map(SqliteApprovalGrantStore::fromRow)
                .toList());
    }

    @Override
    public ApprovalGrant consumeOnce(ApprovalGrantId id, long expectedVersion, Instant consumedAt) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(consumedAt, "consumedAt must not be null");
        return SqlitePolicyStoreSupport.execute(unitOfWork, () -> {
            PolicyStoreMapper mapper = unitOfWork.mapper(PolicyStoreMapper.class);
            if (mapper.consumeApprovalGrant(id.value(), expectedVersion, consumedAt) != 1) {
                throw new IllegalStateException("grant is unavailable, expired, consumed, or version-conflicted");
            }
            return fromRow(mapper.findApprovalGrant(id.value()));
        });
    }

    @Override
    public ApprovalGrant revoke(ApprovalGrantId id, long expectedVersion, Instant revokedAt) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(revokedAt, "revokedAt must not be null");
        return SqlitePolicyStoreSupport.execute(unitOfWork, () -> {
            PolicyStoreMapper mapper = unitOfWork.mapper(PolicyStoreMapper.class);
            if (mapper.revokeApprovalGrant(id.value(), expectedVersion, revokedAt, "GRANT_REVOKED") != 1) {
                throw new IllegalStateException("grant is unavailable or version-conflicted");
            }
            return fromRow(mapper.findApprovalGrant(id.value()));
        });
    }

    private static ApprovalGrantRow toRow(ApprovalGrant grant) {
        return new ApprovalGrantRow(
                grant.id().value(),
                grant.semantics().name(),
                grant.reuseScope().name(),
                grant.subject().tenant().tenantId(),
                grant.subject().principal().principalId(),
                grant.subject().principal().principalType(),
                grant.subject().productId(),
                grant.action().capability(),
                grant.action().operation(),
                grant.target().targetType(),
                grant.target().targetId(),
                grant.target().targetVersion(),
                grant.target().operation(),
                grant.target().targetDigest(),
                grant.target().safeSummary(),
                grant.sessionRef().orElse(null),
                grant.projectRef().orElse(null),
                grant.projectTrustRef().map(ProjectTrustRef::value).orElse(null),
                grant.securityConfigurationDigest().orElse(null),
                grant.sourceDecisionId().value(),
                grant.sourceApprovalRequestRef(),
                grant.sourceApprovalResponseRef(),
                grant.createdBy().tenant().tenantId(),
                grant.createdBy().principal().principalId(),
                grant.createdBy().principal().principalType(),
                grant.createdAt(),
                grant.expiresAt().orElse(null),
                grant.revokedAt().orElse(null),
                grant.revocationReasonCode().orElse(null),
                grant.consumedAt().orElse(null),
                grant.state().name(),
                grant.version());
    }

    private static ApprovalGrant fromRow(ApprovalGrantRow row) {
        return new ApprovalGrant(
                new ApprovalGrantId(row.grantId()),
                ApprovalSemantics.valueOf(row.semantics()),
                ApprovalReuseScope.valueOf(row.reuseScope()),
                new PolicySubject(
                        new TenantRef(row.tenantId()),
                        new PrincipalRef(row.requesterPrincipalId(), row.requesterPrincipalType()),
                        row.productId()),
                new PolicyAction(row.capability(), row.operation()),
                new ApprovalTargetRef(
                        row.targetType(),
                        row.targetId(),
                        row.targetVersion(),
                        row.targetOperation(),
                        row.targetDigest(),
                        row.targetSafeSummary()),
                Optional.ofNullable(row.sessionRef()),
                Optional.ofNullable(row.projectRef()),
                Optional.ofNullable(row.projectTrustRef()).map(ProjectTrustRef::new),
                Optional.ofNullable(row.authorizationConfigurationDigest()),
                new PolicyDecisionId(row.sourceDecisionId()),
                row.sourceApprovalRequestId(),
                row.sourceApprovalResponseId(),
                new ApprovalResponder(
                        new TenantRef(row.responderTenantId()),
                        new PrincipalRef(row.responderPrincipalId(), row.responderPrincipalType())),
                row.createdAt(),
                Optional.ofNullable(row.expiresAt()),
                ApprovalGrantState.valueOf(row.state()),
                Optional.ofNullable(row.revokedAt()),
                Optional.ofNullable(row.revocationReasonCode()),
                Optional.ofNullable(row.consumedAt()),
                row.version());
    }
}

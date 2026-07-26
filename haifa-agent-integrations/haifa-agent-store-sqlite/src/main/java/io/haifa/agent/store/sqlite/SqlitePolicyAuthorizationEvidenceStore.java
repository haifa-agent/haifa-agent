package io.haifa.agent.store.sqlite;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.policy.api.ApprovalRequester;
import io.haifa.agent.policy.api.ApprovalResponder;
import io.haifa.agent.policy.api.PolicyAuthorizationEvidence;
import io.haifa.agent.policy.api.PolicyAuthorizationEvidenceStore;
import io.haifa.agent.policy.api.PolicyDecisionId;
import io.haifa.agent.store.sqlite.mybatis.PolicyAuthorizationEvidenceRow;
import io.haifa.agent.store.sqlite.mybatis.PolicyStoreMapper;
import java.util.Objects;
import java.util.Optional;

public final class SqlitePolicyAuthorizationEvidenceStore implements PolicyAuthorizationEvidenceStore {
    private final SqliteRuntimeUnitOfWork unitOfWork;

    public SqlitePolicyAuthorizationEvidenceStore(SqliteRuntimeUnitOfWork unitOfWork) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
    }

    @Override
    public void save(PolicyAuthorizationEvidence value) {
        Objects.requireNonNull(value, "value must not be null");
        SqlitePolicyStoreSupport.execute(unitOfWork, () -> {
            PolicyStoreMapper mapper = unitOfWork.mapper(PolicyStoreMapper.class);
            PolicyAuthorizationEvidenceRow existing =
                    mapper.findPolicyAuthorizationEvidence(value.decisionId().value());
            if (existing != null) {
                if (!fromRow(existing).equals(value)) {
                    throw new IllegalStateException("policy authorization evidence already exists");
                }
                return null;
            }
            mapper.insertPolicyAuthorizationEvidence(toRow(value));
            return null;
        });
    }

    @Override
    public Optional<PolicyAuthorizationEvidence> find(PolicyDecisionId decisionId) {
        Objects.requireNonNull(decisionId, "decisionId must not be null");
        return SqlitePolicyStoreSupport.execute(unitOfWork, () -> Optional.ofNullable(
                        unitOfWork.mapper(PolicyStoreMapper.class).findPolicyAuthorizationEvidence(decisionId.value()))
                .map(SqlitePolicyAuthorizationEvidenceStore::fromRow));
    }

    private static PolicyAuthorizationEvidenceRow toRow(PolicyAuthorizationEvidence value) {
        return new PolicyAuthorizationEvidenceRow(
                value.decisionId().value(),
                value.requestDigest(),
                value.requester().tenant().tenantId(),
                value.requester().principal().principalId(),
                value.requester().principal().principalType(),
                value.responder().tenant().tenantId(),
                value.responder().principal().principalId(),
                value.responder().principal().principalType(),
                value.approvedAt(),
                value.expiresAt());
    }

    private static PolicyAuthorizationEvidence fromRow(PolicyAuthorizationEvidenceRow row) {
        return new PolicyAuthorizationEvidence(
                new PolicyDecisionId(row.decisionId()),
                row.requestDigest(),
                new ApprovalRequester(
                        new TenantRef(row.requesterTenantId()),
                        new PrincipalRef(row.requesterPrincipalId(), row.requesterPrincipalType())),
                new ApprovalResponder(
                        new TenantRef(row.responderTenantId()),
                        new PrincipalRef(row.responderPrincipalId(), row.responderPrincipalType())),
                row.approvedAt(),
                row.expiresAt());
    }
}

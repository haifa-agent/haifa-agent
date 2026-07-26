package io.haifa.agent.store.sqlite;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.policy.api.ProjectTrust;
import io.haifa.agent.policy.api.ProjectTrustRef;
import io.haifa.agent.policy.api.ProjectTrustState;
import io.haifa.agent.policy.api.ProjectTrustStore;
import io.haifa.agent.store.sqlite.mybatis.PolicyStoreMapper;
import io.haifa.agent.store.sqlite.mybatis.ProjectTrustRow;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class SqliteProjectTrustStore implements ProjectTrustStore {
    private final SqliteRuntimeUnitOfWork unitOfWork;

    public SqliteProjectTrustStore(SqliteRuntimeUnitOfWork unitOfWork) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
    }

    @Override
    public void save(ProjectTrust trust) {
        Objects.requireNonNull(trust, "trust must not be null");
        SqlitePolicyStoreSupport.execute(unitOfWork, () -> {
            PolicyStoreMapper mapper = unitOfWork.mapper(PolicyStoreMapper.class);
            ProjectTrustRow existing = mapper.findProjectTrust(trust.ref().value());
            if (existing != null) {
                if (!fromRow(existing).equals(trust)) {
                    throw new IllegalStateException("project trust id is already used");
                }
                return null;
            }
            mapper.insertProjectTrust(toRow(trust));
            return null;
        });
    }

    @Override
    public Optional<ProjectTrust> find(ProjectTrustRef ref) {
        Objects.requireNonNull(ref, "ref must not be null");
        return SqlitePolicyStoreSupport.execute(unitOfWork, () -> Optional.ofNullable(
                        unitOfWork.mapper(PolicyStoreMapper.class).findProjectTrust(ref.value()))
                .map(SqliteProjectTrustStore::fromRow));
    }

    @Override
    public ProjectTrust revoke(ProjectTrustRef ref, long expectedVersion, Instant revokedAt) {
        Objects.requireNonNull(ref, "ref must not be null");
        Objects.requireNonNull(revokedAt, "revokedAt must not be null");
        return SqlitePolicyStoreSupport.execute(unitOfWork, () -> {
            PolicyStoreMapper mapper = unitOfWork.mapper(PolicyStoreMapper.class);
            if (mapper.revokeProjectTrust(ref.value(), expectedVersion, revokedAt, "PROJECT_TRUST_REVOKED") != 1) {
                throw new IllegalStateException("project trust is unavailable or version-conflicted");
            }
            return fromRow(mapper.findProjectTrust(ref.value()));
        });
    }

    private static ProjectTrustRow toRow(ProjectTrust trust) {
        return new ProjectTrustRow(
                trust.ref().value(),
                trust.tenant().tenantId(),
                trust.principal().principalId(),
                trust.principal().principalType(),
                trust.projectRef(),
                trust.canonicalProjectIdentity(),
                trust.trustedRootIdentity(),
                trust.securityConfigurationDigest(),
                trust.productProfileRef(),
                trust.state().name(),
                trust.confirmedAt(),
                trust.expiresAt().orElse(null),
                trust.revokedAt().orElse(null),
                trust.revocationReasonCode().orElse(null),
                trust.version());
    }

    private static ProjectTrust fromRow(ProjectTrustRow row) {
        return new ProjectTrust(
                new ProjectTrustRef(row.trustId()),
                new TenantRef(row.tenantId()),
                new PrincipalRef(row.principalId(), row.principalType()),
                row.projectRef(),
                row.canonicalProjectIdentity(),
                row.trustedRootIdentity(),
                row.authorizationConfigurationDigest(),
                row.productProfileRef(),
                ProjectTrustState.valueOf(row.state()),
                row.confirmedAt(),
                Optional.ofNullable(row.expiresAt()),
                Optional.ofNullable(row.revokedAt()),
                Optional.ofNullable(row.revocationReasonCode()),
                row.version());
    }
}

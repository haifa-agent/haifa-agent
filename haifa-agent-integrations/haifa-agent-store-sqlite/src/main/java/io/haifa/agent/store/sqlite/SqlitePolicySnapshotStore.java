package io.haifa.agent.store.sqlite;

import io.haifa.agent.policy.api.ApprovalMode;
import io.haifa.agent.policy.api.PolicySnapshot;
import io.haifa.agent.policy.api.PolicySnapshotRef;
import io.haifa.agent.policy.api.PolicySnapshotStore;
import io.haifa.agent.policy.api.ProjectTrustRef;
import io.haifa.agent.store.sqlite.codec.EncodedPayload;
import io.haifa.agent.store.sqlite.codec.VersionedPayloadCodecRegistry;
import io.haifa.agent.store.sqlite.mybatis.PolicySnapshotRow;
import io.haifa.agent.store.sqlite.mybatis.PolicyStoreMapper;
import io.haifa.agent.store.sqlite.payload.SqliteRuntimePayloadTypes;
import java.util.Objects;
import java.util.Optional;

public final class SqlitePolicySnapshotStore implements PolicySnapshotStore {
    private final SqliteRuntimeUnitOfWork unitOfWork;
    private final VersionedPayloadCodecRegistry codecs;

    public SqlitePolicySnapshotStore(SqliteRuntimeUnitOfWork unitOfWork, VersionedPayloadCodecRegistry codecs) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        this.codecs = Objects.requireNonNull(codecs, "codecs must not be null");
    }

    @Override
    public void save(PolicySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        SqlitePolicyStoreSupport.execute(unitOfWork, () -> {
            PolicyStoreMapper mapper = unitOfWork.mapper(PolicyStoreMapper.class);
            PolicySnapshotRow existing =
                    mapper.findPolicySnapshot(snapshot.ref().value());
            if (existing != null) {
                if (!fromRow(existing).equals(snapshot)) {
                    throw new IllegalStateException("policy snapshot id is already used");
                }
                return null;
            }
            EncodedPayload payload = codecs.encode(SqliteRuntimePayloadTypes.POLICY_SNAPSHOT, snapshot);
            mapper.insertPolicySnapshot(new PolicySnapshotRow(
                    snapshot.ref().value(),
                    "unscoped",
                    snapshot.productProfileRef(),
                    snapshot.approvalMode().name(),
                    snapshot.productProfileRef(),
                    snapshot.projectTrustRef().map(ProjectTrustRef::value).orElse(null),
                    payload.schemaVersion(),
                    payload.bytes(),
                    payload.hash(),
                    snapshot.contentDigest(),
                    snapshot.createdAt()));
            return null;
        });
    }

    @Override
    public Optional<PolicySnapshot> find(PolicySnapshotRef ref) {
        Objects.requireNonNull(ref, "ref must not be null");
        return SqlitePolicyStoreSupport.execute(unitOfWork, () -> Optional.ofNullable(
                        unitOfWork.mapper(PolicyStoreMapper.class).findPolicySnapshot(ref.value()))
                .map(this::fromRow));
    }

    private PolicySnapshot fromRow(PolicySnapshotRow row) {
        PolicySnapshot snapshot = codecs.decode(
                SqliteRuntimePayloadTypes.POLICY_SNAPSHOT,
                new EncodedPayload(
                        SqliteRuntimePayloadTypes.POLICY_SNAPSHOT.name(),
                        row.rulesSchemaVersion(),
                        row.rulesPayload(),
                        row.rulesHash()));
        if (!snapshot.ref().value().equals(row.snapshotId())
                || snapshot.approvalMode() != ApprovalMode.valueOf(row.approvalMode())
                || !snapshot.productProfileRef().equals(row.productProfileRef())
                || !snapshot.projectTrustRef()
                        .map(ProjectTrustRef::value)
                        .equals(Optional.ofNullable(row.projectTrustRef()))
                || !snapshot.contentDigest().equals(row.contentDigest())
                || snapshot.createdAt().toEpochMilli() != row.createdAt().toEpochMilli()) {
            throw new IllegalStateException("policy snapshot columns do not match payload");
        }
        return snapshot;
    }
}

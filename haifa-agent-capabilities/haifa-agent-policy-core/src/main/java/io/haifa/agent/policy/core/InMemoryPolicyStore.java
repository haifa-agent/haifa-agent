package io.haifa.agent.policy.core;

import io.haifa.agent.policy.api.ApprovalGrant;
import io.haifa.agent.policy.api.ApprovalGrantId;
import io.haifa.agent.policy.api.ApprovalGrantQuery;
import io.haifa.agent.policy.api.ApprovalGrantStore;
import io.haifa.agent.policy.api.PolicyDecision;
import io.haifa.agent.policy.api.PolicyDecisionId;
import io.haifa.agent.policy.api.PolicyDecisionStore;
import io.haifa.agent.policy.api.PolicySnapshot;
import io.haifa.agent.policy.api.PolicySnapshotRef;
import io.haifa.agent.policy.api.PolicySnapshotStore;
import io.haifa.agent.policy.api.ProjectTrust;
import io.haifa.agent.policy.api.ProjectTrustRef;
import io.haifa.agent.policy.api.ProjectTrustStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryPolicyStore
        implements PolicySnapshotStore, PolicyDecisionStore, ApprovalGrantStore, ProjectTrustStore {
    private final Map<PolicySnapshotRef, PolicySnapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<PolicyDecisionId, PolicyDecision> decisions = new ConcurrentHashMap<>();
    private final Map<ApprovalGrantId, ApprovalGrant> grants = new ConcurrentHashMap<>();
    private final Map<ProjectTrustRef, ProjectTrust> trusts = new ConcurrentHashMap<>();

    @Override
    public void save(PolicySnapshot snapshot) {
        putExact(snapshots, snapshot.ref(), snapshot, "snapshot");
    }

    @Override
    public Optional<PolicySnapshot> find(PolicySnapshotRef ref) {
        return Optional.ofNullable(snapshots.get(Objects.requireNonNull(ref, "ref must not be null")));
    }

    @Override
    public void save(PolicyDecision decision) {
        putExact(decisions, decision.id(), decision, "decision");
    }

    @Override
    public Optional<PolicyDecision> find(PolicyDecisionId id) {
        return Optional.ofNullable(decisions.get(Objects.requireNonNull(id, "id must not be null")));
    }

    @Override
    public void save(ApprovalGrant grant) {
        putExact(grants, grant.id(), grant, "grant");
    }

    @Override
    public Optional<ApprovalGrant> find(ApprovalGrantId id) {
        return Optional.ofNullable(grants.get(Objects.requireNonNull(id, "id must not be null")));
    }

    @Override
    public List<ApprovalGrant> findCandidates(ApprovalGrantQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        return grants.values().stream()
                .filter(grant -> grant.subject().equals(query.subject()))
                .filter(grant -> grant.action().equals(query.action()))
                .filter(grant ->
                        grant.target().targetType().equals(query.target().targetType()))
                .sorted((left, right) -> left.id().value().compareTo(right.id().value()))
                .toList();
    }

    @Override
    public synchronized ApprovalGrant consumeOnce(ApprovalGrantId id, long expectedVersion, Instant consumedAt) {
        ApprovalGrant current = require(grants, id, "grant");
        requireVersion(current.version(), expectedVersion);
        ApprovalGrant updated = current.consume(consumedAt);
        grants.put(id, updated);
        return updated;
    }

    @Override
    public synchronized ApprovalGrant revoke(ApprovalGrantId id, long expectedVersion, Instant revokedAt) {
        ApprovalGrant current = require(grants, id, "grant");
        requireVersion(current.version(), expectedVersion);
        ApprovalGrant updated = current.revoke(revokedAt);
        grants.put(id, updated);
        return updated;
    }

    @Override
    public void save(ProjectTrust trust) {
        putExact(trusts, trust.ref(), trust, "project trust");
    }

    @Override
    public Optional<ProjectTrust> find(ProjectTrustRef ref) {
        return Optional.ofNullable(trusts.get(Objects.requireNonNull(ref, "ref must not be null")));
    }

    @Override
    public synchronized ProjectTrust revoke(ProjectTrustRef ref, long expectedVersion, Instant revokedAt) {
        ProjectTrust current = require(trusts, ref, "project trust");
        requireVersion(current.version(), expectedVersion);
        ProjectTrust updated = current.revoke(revokedAt);
        trusts.put(ref, updated);
        return updated;
    }

    private static <K, V> void putExact(Map<K, V> values, K key, V value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        V existing = values.putIfAbsent(key, value);
        if (existing != null && !existing.equals(value)) {
            throw new IllegalStateException(name + " id is already used");
        }
    }

    private static <K, V> V require(Map<K, V> values, K key, String name) {
        V value = values.get(Objects.requireNonNull(key, "key must not be null"));
        if (value == null) throw new IllegalArgumentException("unknown " + name);
        return value;
    }

    private static void requireVersion(long actual, long expected) {
        if (actual != expected) throw new IllegalStateException("version conflict");
    }
}

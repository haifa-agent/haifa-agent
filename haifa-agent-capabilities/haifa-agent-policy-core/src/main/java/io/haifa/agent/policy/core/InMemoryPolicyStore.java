package io.haifa.agent.policy.core;

import io.haifa.agent.policy.api.PolicySnapshot;
import io.haifa.agent.policy.api.PolicySnapshotRef;
import io.haifa.agent.policy.api.PolicySnapshotStore;
import io.haifa.agent.policy.api.ProjectTrust;
import io.haifa.agent.policy.api.ProjectTrustRef;
import io.haifa.agent.policy.api.ProjectTrustStore;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryPolicyStore implements PolicySnapshotStore, ProjectTrustStore {
    private final Map<PolicySnapshotRef, PolicySnapshot> snapshots = new ConcurrentHashMap<>();
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

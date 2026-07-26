package io.haifa.agent.policy.api;

import java.util.Optional;

public interface PolicySnapshotStore {
    void save(PolicySnapshot snapshot);

    Optional<PolicySnapshot> find(PolicySnapshotRef ref);
}

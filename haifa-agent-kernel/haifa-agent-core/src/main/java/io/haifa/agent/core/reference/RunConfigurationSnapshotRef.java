package io.haifa.agent.core.reference;

import static io.haifa.agent.core.support.DomainValues.requireText;

/** Immutable content-addressed reference to all effective run configuration. */
public record RunConfigurationSnapshotRef(String snapshotId, String contentHash) {
    public RunConfigurationSnapshotRef {
        snapshotId = requireText(snapshotId, "snapshotId");
        contentHash = requireText(contentHash, "contentHash");
    }
}

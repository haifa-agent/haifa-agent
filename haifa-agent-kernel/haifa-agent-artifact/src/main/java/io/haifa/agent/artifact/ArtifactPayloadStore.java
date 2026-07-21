package io.haifa.agent.artifact;

import java.util.Optional;

public interface ArtifactPayloadStore {
    ArtifactPayloadRef put(byte[] payload, String mediaType);

    Optional<byte[]> load(ArtifactPayloadRef reference);

    void delete(ArtifactPayloadRef reference);
}

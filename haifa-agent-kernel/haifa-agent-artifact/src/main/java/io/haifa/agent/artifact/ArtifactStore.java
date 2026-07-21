package io.haifa.agent.artifact;

import java.util.List;
import java.util.Optional;

public interface ArtifactStore {
    void create(Artifact artifact);

    Optional<Artifact> find(ArtifactId id, ArtifactVersion version);

    List<Artifact> findByProject(String projectId);
}

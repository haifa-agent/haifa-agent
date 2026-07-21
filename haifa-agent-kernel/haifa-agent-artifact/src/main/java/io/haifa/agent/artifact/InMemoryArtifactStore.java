package io.haifa.agent.artifact;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryArtifactStore implements ArtifactStore {
    private final ConcurrentHashMap<Key, Artifact> values = new ConcurrentHashMap<>();

    @Override
    public void create(Artifact artifact) {
        if (values.putIfAbsent(new Key(artifact.id(), artifact.version()), artifact) != null) {
            throw new IllegalStateException("artifact version already exists");
        }
    }

    @Override
    public Optional<Artifact> find(ArtifactId id, ArtifactVersion version) {
        return Optional.ofNullable(values.get(new Key(id, version)));
    }

    @Override
    public List<Artifact> findByProject(String projectId) {
        return values.values().stream()
                .filter(value -> value.provenance().project().projectId().equals(projectId))
                .sorted(Comparator.comparing((Artifact value) -> value.id().value())
                        .thenComparing(Artifact::version))
                .toList();
    }

    private record Key(ArtifactId id, ArtifactVersion version) {}
}

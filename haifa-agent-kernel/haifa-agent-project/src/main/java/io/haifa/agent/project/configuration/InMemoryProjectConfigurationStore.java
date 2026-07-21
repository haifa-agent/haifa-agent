package io.haifa.agent.project.configuration;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryProjectConfigurationStore implements ProjectConfigurationStore {
    private final ConcurrentHashMap<Key, ProjectConfiguration> values = new ConcurrentHashMap<>();

    @Override
    public void publish(ProjectConfiguration configuration) {
        ProjectConfiguration previous =
                values.putIfAbsent(new Key(configuration.id(), configuration.version()), configuration);
        if (previous != null && !previous.equals(configuration)) {
            throw new IllegalStateException("project configuration version is immutable");
        }
    }

    @Override
    public Optional<ProjectConfiguration> find(ProjectConfigurationId id, ProjectConfigurationVersion version) {
        return Optional.ofNullable(values.get(new Key(id, version)));
    }

    private record Key(ProjectConfigurationId id, ProjectConfigurationVersion version) {}
}

package io.haifa.agent.project.configuration;

import java.util.Optional;

public interface ProjectConfigurationStore {
    void publish(ProjectConfiguration configuration);

    Optional<ProjectConfiguration> find(ProjectConfigurationId id, ProjectConfigurationVersion version);
}

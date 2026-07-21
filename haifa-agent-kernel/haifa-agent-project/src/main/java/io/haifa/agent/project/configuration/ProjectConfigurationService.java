package io.haifa.agent.project.configuration;

import io.haifa.agent.project.domain.ProjectConfigurationRef;
import java.util.Objects;

public final class ProjectConfigurationService {
    private final ProjectConfigurationStore configurations;

    public ProjectConfigurationService(ProjectConfigurationStore configurations) {
        this.configurations = Objects.requireNonNull(configurations, "configurations must not be null");
    }

    public void publish(ProjectConfiguration configuration) {
        configurations.publish(configuration);
    }

    public ProjectConfiguration resolve(ProjectConfigurationRef reference) {
        return configurations
                .find(
                        new ProjectConfigurationId(reference.configurationId()),
                        new ProjectConfigurationVersion(reference.version()))
                .orElseThrow(() -> new IllegalStateException("project configuration not found"));
    }
}

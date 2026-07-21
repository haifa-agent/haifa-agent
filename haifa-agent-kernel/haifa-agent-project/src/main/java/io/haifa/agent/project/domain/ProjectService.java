package io.haifa.agent.project.domain;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.project.store.ProjectStore;
import java.util.Map;
import java.util.Objects;

public final class ProjectService {
    private final ProjectStore projects;
    private final IdentifierGenerator identifiers;
    private final TimeProvider time;

    public ProjectService(ProjectStore projects, IdentifierGenerator identifiers, TimeProvider time) {
        this.projects = Objects.requireNonNull(projects, "projects must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
    }

    public Project create(
            TenantRef tenant,
            PrincipalRef owner,
            String name,
            String description,
            ProjectConfigurationRef configuration,
            Map<String, String> metadata) {
        Project project = Project.create(
                new ProjectId(identifiers.nextValue()),
                tenant,
                owner,
                name,
                description,
                configuration,
                time.now(),
                metadata);
        projects.create(project);
        return project;
    }

    public Project archive(ProjectId id) {
        Project current = require(id);
        Project archived = current.archive(time.now());
        projects.save(archived, current.version());
        return archived;
    }

    public Project activate(ProjectId id) {
        Project current = require(id);
        Project active = current.activate(time.now());
        projects.save(active, current.version());
        return active;
    }

    public Project require(ProjectId id) {
        return projects.find(id).orElseThrow(() -> new IllegalArgumentException("project not found: " + id.value()));
    }
}

package io.haifa.agent.application.project.product;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.content.AssetRefPart;
import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.reference.AssetRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.project.configuration.ProjectConfigurationService;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.domain.ProjectStatus;
import io.haifa.agent.project.store.ProjectStore;
import io.haifa.agent.project.store.WorkspaceStore;
import io.haifa.agent.project.workspace.WorkspaceStatus;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.AgentRuntime;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Project-only product façade: Workspace and security choices never appear in public start/continue methods. */
public final class ProjectProductService {
    private final ProjectStore projects;
    private final WorkspaceStore workspaces;
    private final ProjectConfigurationService configurations;
    private final ProjectProductSessionStore sessions;
    private final ProjectSessionProvisioner sessionProvisioner;
    private final TrustedProductCallerProvider callers;
    private final AgentRuntime runtime;
    private final IdentifierGenerator identifiers;
    private final AgentDefinitionId definitionId;

    public ProjectProductService(
            ProjectStore projects,
            WorkspaceStore workspaces,
            ProjectConfigurationService configurations,
            ProjectProductSessionStore sessions,
            ProjectSessionProvisioner sessionProvisioner,
            TrustedProductCallerProvider callers,
            AgentRuntime runtime,
            IdentifierGenerator identifiers,
            AgentDefinitionId definitionId) {
        this.projects = Objects.requireNonNull(projects, "projects must not be null");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces must not be null");
        this.configurations = Objects.requireNonNull(configurations, "configurations must not be null");
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.sessionProvisioner = Objects.requireNonNull(sessionProvisioner, "sessionProvisioner must not be null");
        this.callers = Objects.requireNonNull(callers, "callers must not be null");
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
        this.definitionId = Objects.requireNonNull(definitionId, "definitionId must not be null");
    }

    public ProjectProductRun start(ProjectId projectId, String message, List<AssetRef> attachments) {
        var caller = callers.current();
        var project = projects.find(projectId)
                .orElseThrow(() -> new ProjectProductException("PROJECT_NOT_FOUND", "Project not found"));
        if (project.status() != ProjectStatus.ACTIVE || !project.tenant().equals(caller.tenant())) {
            throw new ProjectProductException("PROJECT_UNAVAILABLE", "Project is unavailable");
        }
        var reference = project.configurationReference()
                .orElseThrow(
                        () -> new ProjectProductException("CONFIGURATION_MISSING", "Project configuration is missing"));
        var configuration = configurations.resolve(reference);
        var workspace = workspaces
                .find(configuration.defaultWorkspaceId())
                .orElseThrow(() -> new ProjectProductException("WORKSPACE_MISSING", "Default workspace is missing"));
        if (!workspace.projectId().equals(projectId)
                || workspace.status() != WorkspaceStatus.ACTIVE
                || project.defaultWorkspace()
                        .filter(configuration.defaultWorkspaceId()::equals)
                        .isEmpty()) {
            throw new ProjectProductException("WORKSPACE_AMBIGUOUS", "Project has no usable default workspace");
        }
        AgentSessionId sessionId = new AgentSessionId(identifiers.nextValue());
        ProjectProductSession productSession = new ProjectProductSession(
                sessionId,
                projectId,
                workspace.id(),
                caller.tenant(),
                caller.principal(),
                configuration.id(),
                configuration.version(),
                configuration.digest(),
                configuration.runtimeProfileRef());
        sessionProvisioner.provision(productSession);
        sessions.create(productSession);
        AgentRunSnapshot snapshot = runtime.start(
                request(project.reference(), sessionId, configuration.runtimeProfileRef(), message, attachments));
        return new ProjectProductRun(sessionId, snapshot, configuration.digest());
    }

    public ProjectProductRun continueSession(AgentSessionId sessionId, String message, List<AssetRef> attachments) {
        var caller = callers.current();
        var session = sessions.find(sessionId)
                .orElseThrow(() -> new ProjectProductException("SESSION_NOT_FOUND", "Session not found"));
        if (!session.tenant().equals(caller.tenant()) || !session.principal().equals(caller.principal())) {
            throw new ProjectProductException("SESSION_DENIED", "Session is unavailable");
        }
        AgentRunSnapshot snapshot = runtime.start(request(
                new ProjectRef(session.projectId().value()),
                sessionId,
                session.productProfileRef(),
                message,
                attachments));
        return new ProjectProductRun(sessionId, snapshot, session.configurationDigest());
    }

    private AgentRunRequest request(
            ProjectRef project,
            AgentSessionId sessionId,
            String profileId,
            String message,
            List<AssetRef> attachments) {
        String objective = requireText(message, "message");
        List<ContentPart> inputs = new ArrayList<>();
        inputs.add(new TextPart(objective, "text/plain"));
        Objects.requireNonNull(attachments, "attachments must not be null")
                .forEach(asset -> inputs.add(new AssetRefPart(asset)));
        return new AgentRunRequest(
                identifiers.nextValue(),
                definitionId,
                Optional.empty(),
                profileId,
                sessionId,
                Optional.of(project),
                objective,
                inputs,
                RuntimeOverrides.NONE);
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    public record ProjectProductRun(AgentSessionId sessionId, AgentRunSnapshot run, String configurationDigest) {
        public ProjectProductRun {
            sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
            run = Objects.requireNonNull(run, "run must not be null");
            configurationDigest = Objects.requireNonNull(configurationDigest, "configurationDigest must not be null");
        }
    }
}

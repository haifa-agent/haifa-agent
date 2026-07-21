package io.haifa.agent.application.project;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.project.product.InMemoryProjectProductSessionStore;
import io.haifa.agent.application.project.product.ProjectProductService;
import io.haifa.agent.application.project.product.TrustedProductCaller;
import io.haifa.agent.application.project.tool.ProjectToolCatalog;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.configuration.InMemoryProjectConfigurationStore;
import io.haifa.agent.project.configuration.ProjectConfiguration;
import io.haifa.agent.project.configuration.ProjectConfigurationId;
import io.haifa.agent.project.configuration.ProjectConfigurationService;
import io.haifa.agent.project.configuration.ProjectConfigurationVersion;
import io.haifa.agent.project.domain.Project;
import io.haifa.agent.project.domain.ProjectConfigurationRef;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.store.InMemoryProjectStore;
import io.haifa.agent.project.store.InMemoryWorkspaceStore;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspacePurpose;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import io.haifa.agent.project.workspace.WorkspaceRoot;
import io.haifa.agent.runtime.api.AgentRunHandle;
import io.haifa.agent.runtime.api.AgentRunListener;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.AgentRuntime;
import io.haifa.agent.runtime.api.InteractionResponse;
import io.haifa.agent.runtime.api.ResumeAgentRunRequest;
import io.haifa.agent.runtime.api.RuntimeCommand;
import io.haifa.agent.runtime.api.RuntimeCommandResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProjectApplicationTest {
    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");

    @Test
    void freezesToolDisclosureAndKeepsOrdinaryChatEmpty() {
        var catalog = new ProjectToolCatalog();
        assertThat(catalog.disclose(Set.of("file.read", "execution.run"), Set.of(), true)
                        .definitions())
                .isEmpty();
        var disclosed = catalog.disclose(
                Set.of("file.read", "file.write", "execution.run"), Set.of("file.read", "execution.run"), true);
        assertThat(disclosed.definitions())
                .extracting(tool -> tool.name())
                .containsExactly("execution.run", "file.read");
        assertThat(disclosed.digest()).startsWith("sha256:");
        assertThat(catalog.disclose(Set.of("file.read"), Set.of("file.read"), false)
                        .definitions())
                .isEmpty();
    }

    @Test
    void projectOnlyFacadeResolvesDefaultWorkspaceAndSupportsMultipleSessions() {
        TenantRef tenant = new TenantRef("tenant-1");
        PrincipalRef principal = new PrincipalRef("principal-1", "user");
        ProjectId projectId = new ProjectId("project-1");
        WorkspaceId workspaceId = new WorkspaceId("workspace-1");
        var projects = new InMemoryProjectStore();
        Project project = Project.create(
                        projectId,
                        tenant,
                        principal,
                        "Demo",
                        "",
                        new ProjectConfigurationRef("config-1", "1"),
                        NOW,
                        java.util.Map.of())
                .assignDefaultWorkspace(workspaceId, NOW);
        projects.create(project);
        var workspaces = new InMemoryWorkspaceStore();
        workspaces.create(Workspace.provision(
                        workspaceId,
                        projectId,
                        WorkspacePurpose.PRIMARY,
                        new WorkspaceRoot(ProjectPath.root(), new WorkspaceBindingId("binding-1"), "test"),
                        WorkspaceRevision.initial("root"),
                        NOW)
                .activate(NOW));
        var configurationStore = new InMemoryProjectConfigurationStore();
        var configuration = ProjectConfiguration.create(
                new ProjectConfigurationId("config-1"),
                new ProjectConfigurationVersion("1"),
                workspaceId,
                "coding",
                "1",
                Set.of("file.read"),
                Set.of("project.workspace.files"),
                Set.of("file.read"),
                "policy-1");
        configurationStore.publish(configuration);
        var runtime = new CapturingRuntime();
        AtomicInteger sequence = new AtomicInteger();
        List<io.haifa.agent.application.project.product.ProjectProductSession> provisioned = new ArrayList<>();
        var service = new ProjectProductService(
                projects,
                workspaces,
                new ProjectConfigurationService(configurationStore),
                new InMemoryProjectProductSessionStore(),
                provisioned::add,
                () -> new TrustedProductCaller(tenant, principal),
                runtime,
                () -> "id-" + sequence.incrementAndGet(),
                new AgentDefinitionId("coding-agent"));

        var first = service.start(projectId, "first task", List.of());
        var second = service.start(projectId, "second task", List.of());
        var continued = service.continueSession(first.sessionId(), "continue", List.of());

        assertThat(first.sessionId()).isNotEqualTo(second.sessionId());
        assertThat(continued.sessionId()).isEqualTo(first.sessionId());
        assertThat(first.configurationDigest()).isEqualTo(configuration.digest());
        assertThat(provisioned).hasSize(2).allSatisfy(session -> {
            assertThat(session.workspaceId()).isEqualTo(workspaceId);
            assertThat(session.configurationDigest()).isEqualTo(configuration.digest());
        });
        assertThat(runtime.requests).hasSize(3).allSatisfy(request -> {
            assertThat(request.project()).contains(project.reference());
            assertThat(request.productProfileId()).isEqualTo("coding@1");
        });
        assertThat(runtime.requests.get(2).sessionId()).isEqualTo(first.sessionId());
    }

    private static final class CapturingRuntime implements AgentRuntime {
        private final List<AgentRunRequest> requests = new ArrayList<>();

        @Override
        public AgentRunSnapshot start(AgentRunRequest request) {
            requests.add(request);
            return new AgentRunSnapshot(
                    new AgentRunId("run-" + requests.size()),
                    AgentRunStatus.PENDING,
                    0,
                    NOW,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());
        }

        @Override
        public AgentRunSnapshot resume(ResumeAgentRunRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AgentRunSnapshot respond(InteractionResponse response) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RuntimeCommandResult command(RuntimeCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<AgentRunSnapshot> find(AgentRunId runId) {
            return Optional.empty();
        }

        @Override
        public AgentRunHandle handle(AgentRunId runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addListener(AgentRunListener listener) {}
    }
}

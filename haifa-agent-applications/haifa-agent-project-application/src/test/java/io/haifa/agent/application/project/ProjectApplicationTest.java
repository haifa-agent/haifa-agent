package io.haifa.agent.application.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.application.project.product.InMemoryProjectProductSessionStore;
import io.haifa.agent.application.project.product.ProjectProductService;
import io.haifa.agent.application.project.product.TrustedProductCaller;
import io.haifa.agent.application.project.tool.ProjectToolCatalog;
import io.haifa.agent.application.project.tool.ProjectToolExecutor;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolResult;
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
import io.haifa.agent.runtime.api.AgentRunOutputEvent;
import io.haifa.agent.runtime.api.AgentRunOutputListener;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.AgentRuntime;
import io.haifa.agent.runtime.api.InteractionResponse;
import io.haifa.agent.runtime.api.ResumeAgentRunRequest;
import io.haifa.agent.runtime.api.RunOutputCursor;
import io.haifa.agent.runtime.api.RuntimeCommand;
import io.haifa.agent.runtime.api.RuntimeCommandResult;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolProvider;
import io.haifa.agent.tool.api.ToolProviderId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ProjectApplicationTest {
    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");

    @Test
    void freezesToolDisclosureAndKeepsOrdinaryChatEmpty() {
        var catalog = new ProjectToolCatalog();
        ToolProvider provider = new ToolProvider() {
            @Override
            public ToolProviderId id() {
                return ProjectToolExecutor.PROVIDER_ID;
            }

            @Override
            public io.haifa.agent.core.tool.ToolResult invoke(ToolInvocationRequest request) {
                throw new UnsupportedOperationException();
            }
        };
        assertThat(catalog.freeze(Set.of("file.read", "execution.run"), Set.of(), true, provider)
                        .snapshot()
                        .bindings())
                .isEmpty();
        var disclosed = catalog.freeze(
                Set.of("file.read", "file.write", "execution.run"),
                Set.of("file.read", "execution.run"),
                true,
                provider);
        assertThat(disclosed.snapshot().bindings())
                .extracting(binding -> binding.alias().value())
                .containsExactly("execution_run", "file_read");
        var execution = disclosed.snapshot().bindings().getFirst();
        @SuppressWarnings("unchecked")
        var properties = (java.util.Map<String, Object>)
                execution.definition().inputSchema().document().get("properties");
        assertThat(properties).containsOnlyKeys("command", "workdir", "timeoutMillis", "description");
        assertThat(execution.definition().inputSchema().document())
                .containsEntry("required", List.of("command"))
                .containsEntry("additionalProperties", false);
        assertThat(execution.definition().outputSchema().document()).containsEntry("additionalProperties", false);
        assertThat(disclosed.snapshot().digest()).matches("[0-9a-f]{64}");
        assertThat(catalog.freeze(Set.of("file.read"), Set.of("file.read"), false, provider)
                        .snapshot()
                        .bindings())
                .isEmpty();
        assertThat(catalog.freeze(Set.of("file.read"), Set.of("file.read"), true, provider, List.of())
                        .snapshot()
                        .bindings())
                .extracting(binding -> binding.alias().value())
                .containsExactly("file_read");
    }

    @Test
    void publishesAllFourteenProjectToolsWithCompleteFrozenDefinitions() {
        var catalog = new ProjectToolCatalog();
        var frozen = catalog.freeze(
                catalog.names(),
                Set.of("file.read", "file.write", "git.read", "execution.run"),
                true,
                providerThatMustNotRun());

        assertThat(frozen.snapshot().bindings())
                .hasSize(14)
                .extracting(binding -> binding.alias().value())
                .containsExactly(
                        "execution_run",
                        "file_create",
                        "file_delete",
                        "file_diff",
                        "file_list",
                        "file_move",
                        "file_patch",
                        "file_read",
                        "file_search",
                        "file_stat",
                        "file_write",
                        "git_diff",
                        "git_inspect",
                        "git_status");
        assertThat(frozen.snapshot().bindings()).allSatisfy(binding -> {
            assertThat(binding.definition().inputSchema().document()).containsKey("$schema");
            assertThat(binding.definition().outputSchema().document()).containsKey("$schema");
            assertThat(binding.definition().sideEffects()).isNotEmpty();
            assertThat(binding.definition().resources().filesystemCapabilities())
                    .isNotEmpty();
            assertThat(binding.coordinate().definitionHash().value()).matches("[0-9a-f]{64}");
        });
    }

    @Test
    void projectProviderPreservesRunWorkspaceAndCapabilityBoundary() {
        var binding = new ProjectToolCatalog()
                .freeze(Set.of("file.read"), Set.of("file.read"), true, providerThatMustNotRun())
                .snapshot()
                .bindings()
                .getFirst();
        WorkspaceId workspaceId = new WorkspaceId("workspace-tool");
        PrincipalRef principal = new PrincipalRef("operator", "user");
        AtomicReference<String> observed = new AtomicReference<>();
        ProjectToolExecutor executor = new ProjectToolExecutor(
                (runId, actor) -> new io.haifa.agent.application.project.tool.RunWorkspaceAccess(
                        workspaceId, Set.of("file.read"), "policy-1"),
                (toolName, workspace, actor, runRef, policy, arguments) -> {
                    observed.set(toolName + "|" + workspace.value() + "|" + actor.principalId() + "|" + runRef + "|"
                            + policy);
                    return new ToolResult(true, "read", java.util.Map.of(), List.of(), List.of(), false);
                });
        var request = new ToolInvocationRequest(
                binding,
                new ToolCallId("tool-call"),
                new AgentRunId("run-tool"),
                new TenantRef("tenant"),
                principal,
                new ToolArguments("haifa.file.read.input", "1.0.0", java.util.Map.of("path", "README.md")),
                NOW.plusSeconds(30),
                Optional.of("key"),
                () -> false,
                List.of());

        assertThat(executor.invoke(request).successful()).isTrue();
        assertThat(observed).hasValue("file.read|workspace-tool|operator|run-tool|policy-1");

        ProjectToolExecutor denied = new ProjectToolExecutor(
                (runId, actor) -> new io.haifa.agent.application.project.tool.RunWorkspaceAccess(
                        workspaceId, Set.of(), "policy-2"),
                (toolName, workspace, actor, runRef, policy, arguments) -> {
                    throw new AssertionError("unauthorized operation must not execute");
                });
        assertThatThrownBy(() -> denied.invoke(request)).isInstanceOf(SecurityException.class);
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

        @Override
        public List<AgentRunOutputEvent> outputEvents(AgentRunId runId, RunOutputCursor after, int limit) {
            return List.of();
        }

        @Override
        public void addOutputListener(AgentRunOutputListener listener) {}
    }

    private static ToolProvider providerThatMustNotRun() {
        return new ToolProvider() {
            @Override
            public ToolProviderId id() {
                return ProjectToolExecutor.PROVIDER_ID;
            }

            @Override
            public ToolResult invoke(ToolInvocationRequest request) {
                throw new AssertionError("catalog test provider must not run");
            }
        };
    }
}

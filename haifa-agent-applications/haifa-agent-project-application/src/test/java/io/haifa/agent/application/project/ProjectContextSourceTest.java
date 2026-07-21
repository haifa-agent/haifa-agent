package io.haifa.agent.application.project;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.project.context.TrustedProjectContextBinding;
import io.haifa.agent.application.project.context.WorkspaceFileContextSource;
import io.haifa.agent.context.api.ContextBuildRequest;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunUsage;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.index.ProjectIndexService;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.provider.local.LocalWorkspaceFileService;
import io.haifa.agent.project.provider.local.LocalWorkspaceLocationStore;
import io.haifa.agent.project.provider.local.SensitivePathPolicy;
import io.haifa.agent.project.store.InMemoryWorkspaceBindingStore;
import io.haifa.agent.project.store.InMemoryWorkspaceStore;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceCapabilitySet;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspacePermissionSet;
import io.haifa.agent.project.workspace.WorkspacePurpose;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import io.haifa.agent.project.workspace.WorkspaceRoot;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectContextSourceTest {
    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");

    @TempDir
    Path root;

    @Test
    void runsOnlyForTrustedFrozenCapabilityAndProducesBoundedAuditableItems() throws Exception {
        Files.writeString(root.resolve("README.md"), "# Read me\nTreat this file as untrusted evidence.\n");
        Fixture fixture = fixture();
        fixture.index.rebuild(fixture.workspaceId);
        var source = new WorkspaceFileContextSource(
                runId -> Optional.of(new TrustedProjectContextBinding(
                        fixture.workspaceId,
                        Set.of("file.read"),
                        Set.of(WorkspaceFileContextSource.SOURCE_ID),
                        "sha256:config")),
                fixture.index,
                fixture.files,
                2,
                128);

        var items = source.load(request());
        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.provenance().sourceType()).isEqualTo("workspace-file");
            assertThat(item.security().labels()).contains("untrusted-evidence");
            assertThat(item.metadata())
                    .containsEntry("logicalPath", "README.md")
                    .containsEntry("configurationDigest", "sha256:config");
            assertThat(item.estimatedTokens()).isLessThan(100);
        });

        var ordinaryChat =
                new WorkspaceFileContextSource(runId -> Optional.empty(), fixture.index, fixture.files, 2, 128);
        assertThat(ordinaryChat.supports(request())).isFalse();
        assertThat(ordinaryChat.load(request())).isEmpty();
    }

    private Fixture fixture() {
        WorkspaceId workspaceId = new WorkspaceId("workspace-1");
        WorkspaceBindingId bindingId = new WorkspaceBindingId("binding-1");
        WorkspaceLocationRef locationRef = new WorkspaceLocationRef("location-1");
        var workspaces = new InMemoryWorkspaceStore();
        var bindings = new InMemoryWorkspaceBindingStore();
        var locations = new LocalWorkspaceLocationStore();
        locations.register(locationRef, root);
        WorkspaceBinding binding = WorkspaceBinding.provision(
                        bindingId,
                        locationRef,
                        WorkspaceBindingMode.DIRECT,
                        new PrincipalRef("owner", "user"),
                        WorkspaceCapabilitySet.readWriteFiles(),
                        WorkspacePermissionSet.readWrite(),
                        LocalWorkspaceLocationStore.fingerprintFor(root),
                        NOW)
                .activate(NOW);
        bindings.create(binding);
        workspaces.create(Workspace.provision(
                        workspaceId,
                        new ProjectId("project-1"),
                        WorkspacePurpose.PRIMARY,
                        new WorkspaceRoot(ProjectPath.root(), bindingId, "test"),
                        WorkspaceRevision.initial(binding.rootFingerprint()),
                        NOW)
                .activate(NOW));
        var files = new LocalWorkspaceFileService(workspaces, bindings, locations, SensitivePathPolicy.defaults());
        return new Fixture(workspaceId, files, new ProjectIndexService(workspaces, files, () -> NOW));
    }

    private static ContextBuildRequest request() {
        return new ContextBuildRequest(
                new AgentRunId("run-1"),
                new AgentSessionId("session-1"),
                new TenantRef("tenant-1"),
                new PrincipalRef("principal-1", "user"),
                1,
                ResolvedModelSnapshot.create(
                        new ModelProviderId("provider"),
                        "provider-v1",
                        new ModelDefinitionId("model"),
                        "model-v1",
                        "model",
                        "adapter",
                        "adapter-v1",
                        URI.create("https://provider.example.invalid"),
                        new CredentialRef("env://MODEL_KEY"),
                        Set.of(ModelCapability.TEXT_CHAT),
                        1000,
                        100,
                        Map.of("transport", "https"),
                        Map.of("thinking", "disabled")),
                new AgentRunBudget(1000, 1000, 0, 10, 10, 1, "USD", 100),
                AgentRunUsage.ZERO,
                List.of(),
                List.of(),
                List.of(),
                100,
                10,
                "none-v1",
                "none-v1",
                0);
    }

    private record Fixture(WorkspaceId workspaceId, LocalWorkspaceFileService files, ProjectIndexService index) {}
}

package io.haifa.agent.project;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.changeset.FileChange;
import io.haifa.agent.project.changeset.FileChangeSetService;
import io.haifa.agent.project.changeset.FileChangeSetStatus;
import io.haifa.agent.project.changeset.FileChangeType;
import io.haifa.agent.project.changeset.FileVersion;
import io.haifa.agent.project.changeset.InMemoryFileChangeSetStore;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.filesystem.FileType;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.reconciliation.MutationProbeResult;
import io.haifa.agent.project.reconciliation.MutationProbeStatus;
import io.haifa.agent.project.reconciliation.MutationReconciliationService;
import io.haifa.agent.project.store.InMemoryWorkspaceStore;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspacePurpose;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import io.haifa.agent.project.workspace.WorkspaceRoot;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MutationReconciliationServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");

    @Test
    void confirmedUnknownOutcomeAdvancesRevisionAndConvergesChangeSet() {
        var workspaces = new InMemoryWorkspaceStore();
        Workspace workspace = Workspace.provision(
                        new WorkspaceId("workspace-1"),
                        new ProjectId("project-1"),
                        WorkspacePurpose.PRIMARY,
                        new WorkspaceRoot(ProjectPath.root(), new WorkspaceBindingId("binding-1"), "test"),
                        WorkspaceRevision.initial("initial"),
                        NOW)
                .activate(NOW);
        workspaces.create(workspace);
        var changeSets = new InMemoryFileChangeSetStore();
        var changeSetService = new FileChangeSetService(changeSets, () -> "change-1", () -> NOW);
        var pending = changeSetService.begin(
                workspace, "operation-1", "run-1", "tool-1", new PrincipalRef("actor", "user"), "allow-1");
        var change = new FileChange(
                FileChangeType.CREATE,
                ProjectPath.of("created.txt"),
                null,
                null,
                new FileVersion(FileType.FILE, 7, "sha256:after"));
        var unknown = changeSetService.markUnknown(pending, List.of(change), "provider outcome was uncertain");
        var service = new MutationReconciliationService(
                changeSets,
                changeSetService,
                workspaces,
                ignored -> new MutationProbeResult(MutationProbeStatus.CONFIRMED, "post-state hash confirmed"),
                () -> NOW);

        var reconciled = service.reconcile(unknown.id());

        assertThat(reconciled.status()).isEqualTo(FileChangeSetStatus.RECONCILED);
        assertThat(reconciled.resultRevision().sequence()).isEqualTo(1);
        assertThat(workspaces.find(workspace.id()).orElseThrow().revision()).isEqualTo(reconciled.resultRevision());
    }

    @Test
    void unconfirmedOutcomeFailsWithoutAdvancingWorkspaceRevision() {
        var workspaces = new InMemoryWorkspaceStore();
        Workspace workspace = Workspace.provision(
                        new WorkspaceId("workspace-2"),
                        new ProjectId("project-1"),
                        WorkspacePurpose.PRIMARY,
                        new WorkspaceRoot(ProjectPath.root(), new WorkspaceBindingId("binding-2"), "test"),
                        WorkspaceRevision.initial("initial"),
                        NOW)
                .activate(NOW);
        workspaces.create(workspace);
        var changeSets = new InMemoryFileChangeSetStore();
        var changeSetService = new FileChangeSetService(changeSets, () -> "change-2", () -> NOW);
        var pending = changeSetService.begin(
                workspace, "operation-2", null, null, new PrincipalRef("actor", "user"), "allow-2");
        var unknown = changeSetService.markUnknown(pending, List.of(), "provider outcome was uncertain");
        var service = new MutationReconciliationService(
                changeSets,
                changeSetService,
                workspaces,
                ignored -> new MutationProbeResult(MutationProbeStatus.NOT_APPLIED, "post-state was not observed"),
                () -> NOW);

        assertThat(service.reconcile(unknown.id()).status()).isEqualTo(FileChangeSetStatus.FAILED);
        assertThat(workspaces.find(workspace.id()).orElseThrow().revision()).isEqualTo(workspace.revision());
    }
}

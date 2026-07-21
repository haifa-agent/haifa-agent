package io.haifa.agent.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.session.AgentSession;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.session.SessionScope;
import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.domain.Project;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.domain.ProjectStatus;
import io.haifa.agent.project.path.FileSystemSemantics;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.store.InMemoryProjectStore;
import io.haifa.agent.project.store.ProjectStoreConflictException;
import io.haifa.agent.project.workspace.WorkspaceCapabilitySet;
import io.haifa.agent.project.workspace.WorkspacePermissionSet;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProjectCoreTest {
    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");
    private static final TenantRef TENANT = new TenantRef("tenant-1");
    private static final PrincipalRef OWNER = new PrincipalRef("owner-1", "user");

    @Test
    void projectIsALongLivedBoundarySharedByMultipleSessions() {
        Project project =
                Project.create(new ProjectId("project-1"), TENANT, OWNER, "demo", "description", null, NOW, Map.of());
        ProjectRef reference = project.reference();
        AgentSession first = AgentSession.open(
                new AgentSessionId("session-1"), TENANT, OWNER, reference, SessionScope.PROJECT, NOW, Map.of());
        AgentSession second = AgentSession.open(
                new AgentSessionId("session-2"), TENANT, OWNER, reference, SessionScope.PROJECT, NOW, Map.of());

        assertThat(first.project()).contains(reference);
        assertThat(second.project()).contains(reference);
        assertThat(project.status()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(project.archive(NOW.plusSeconds(1)).status()).isEqualTo(ProjectStatus.ARCHIVED);
    }

    @Test
    void inMemoryStoreUsesCompareAndSetVersions() {
        InMemoryProjectStore store = new InMemoryProjectStore();
        Project project = Project.create(new ProjectId("project-1"), TENANT, OWNER, "demo", "", null, NOW, Map.of());
        store.create(project);
        Project archived = project.archive(NOW.plusSeconds(1));
        store.save(archived, 0);

        assertThatThrownBy(() -> store.save(archived.activate(NOW.plusSeconds(2)), 0))
                .isInstanceOf(ProjectStoreConflictException.class);
    }

    @Test
    void projectPathRejectsHostAndTraversalFormsAndHasPlatformComparisonKeys() {
        assertThat(ProjectPath.of("src\\main//java").value()).isEqualTo("src/main/java");
        assertThat(ProjectPath.root().isRoot()).isTrue();
        assertThat(ProjectPath.of("Readme.md").comparisonKey(FileSystemSemantics.windowsDefault()))
                .isEqualTo(ProjectPath.of("README.md").comparisonKey(FileSystemSemantics.windowsDefault()));
        assertThat(ProjectPath.of("Readme.md").comparisonKey(FileSystemSemantics.linuxDefault()))
                .isNotEqualTo(ProjectPath.of("README.md").comparisonKey(FileSystemSemantics.linuxDefault()));
        assertThat(ProjectPath.of("café.md").comparisonKey(FileSystemSemantics.macDefault()))
                .isEqualTo(ProjectPath.of("cafe\u0301.md").comparisonKey(FileSystemSemantics.macDefault()));

        for (String input : new String[] {
            "../secret",
            "/etc/passwd",
            "C:\\secret",
            "\\\\server\\share",
            "file://secret",
            "a/./b",
            "a/../b",
            "NUL.txt",
            "config:stream",
            "bad."
        }) {
            assertThatThrownBy(() -> ProjectPath.of(input)).isInstanceOf(IllegalArgumentException.class);
        }
        for (int index = 0; index < 250; index++) {
            String safe = "dir-" + index + "/file-" + (index * 17) + ".txt";
            assertThat(ProjectPath.of(safe).value()).isEqualTo(safe);
        }
    }

    @Test
    void phaseOneRejectsUnimplementedBindingModesAndReadOnlyCannotGrantWrites() {
        var location = new WorkspaceLocationRef("local-1");
        assertThatThrownBy(() -> WorkspaceBinding.provision(
                        new WorkspaceBindingId("binding-1"),
                        location,
                        WorkspaceBindingMode.COPY_ON_WRITE,
                        OWNER,
                        WorkspaceCapabilitySet.readOnlyFiles(),
                        WorkspacePermissionSet.readOnly(),
                        "sha256:root",
                        NOW))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}

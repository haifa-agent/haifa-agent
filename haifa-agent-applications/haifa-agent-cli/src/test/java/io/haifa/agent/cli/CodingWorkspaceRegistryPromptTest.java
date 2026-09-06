package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.project.hostworkspace.registry.HostWorkspaceRegistrySource;
import io.haifa.agent.project.hostworkspace.registry.HostWorkspaceRegistryStatus;
import io.haifa.agent.project.hostworkspace.registry.HostWorkspaceRegistryView;
import io.haifa.agent.project.hostworkspace.scope.HostDirectoryPermission;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CodingWorkspaceRegistryPromptTest {
    @Test
    void rendersOnlyPathRedactedWorkspaceFacts() {
        String prompt = CodingWorkspaceRegistryPrompt.render(List.of(new HostWorkspaceRegistryView(
                "workspace-ref-1",
                "docs-safe",
                HostDirectoryPermission.READ_ONLY,
                HostWorkspaceRegistrySource.APPROVED_ATTACH,
                HostWorkspaceRegistryStatus.ACTIVE)));

        assertThat(prompt)
                .contains("workspace-ref-1", "docs-safe", "READ_ONLY", "APPROVED_ATTACH", "ACTIVE")
                .contains("host-absolute-file-paths")
                .contains("workspace-ref-plus-relative-workdir")
                .doesNotContain("C:\\", "/home/", "realPath", "locationRef");
    }

    @Test
    void worktreeApprovalShowsTheExactStructuredTargetWithoutAcceptingAHostPath() {
        String prompt = LocalCodingAgent.workspaceWorktreeApprovalPrompt(Map.of(
                "sourceWorkspaceRef", "workspace-ref-1",
                "baseCommit", "abc123",
                "branchName", "feat/example",
                "targetName", "review-copy",
                "permission", "read-write",
                "deliveryIntent", "pull-request"));

        assertThat(prompt)
                .contains(
                        "Source workspace: workspace-ref-1",
                        "Base commit: abc123",
                        "New branch: feat/example",
                        "Managed target: review-copy",
                        "Permission: read-write",
                        "Delivery intent: pull-request",
                        "no arbitrary host path is accepted")
                .doesNotContain("C:\\", "/home/", "targetPath");
    }
}

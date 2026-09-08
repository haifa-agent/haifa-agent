package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.project.product.coding.CodingWorkspaceView;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CodingWorkspaceRegistryPromptTest {
    @Test
    void rendersOnlyPathRedactedWorkspaceFacts() {
        String prompt = CodingWorkspaceRegistryPrompt.render(List.of(
                new CodingWorkspaceView("workspace-ref-1", "docs-safe", "READ", "APPROVED_ATTACH", "ACTIVE", true)));

        assertThat(prompt)
                .contains("workspace-ref-1", "docs-safe", "READ", "APPROVED_ATTACH", "ACTIVE")
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
                "deliveryIntent", "pull-request"));

        assertThat(prompt)
                .contains(
                        "Source workspace: workspace-ref-1",
                        "Base commit: abc123",
                        "New branch: feat/example",
                        "Managed target: review-copy",
                        "Delivery intent: pull-request",
                        "no arbitrary host path is accepted")
                .doesNotContain("C:\\", "/home/", "targetPath");
    }
}

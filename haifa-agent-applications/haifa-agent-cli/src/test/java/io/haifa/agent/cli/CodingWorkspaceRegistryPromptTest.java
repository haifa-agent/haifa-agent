package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.application.project.workspace.WorkspaceAccessMode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CodingWorkspaceRegistryPromptTest {
    @Test
    void rendersMinimalWorkspacePathsBlockWithCurrentFirst() {
        var main = new CodingWorkspaceRegistryPrompt.Entry(
                "workspace-main", "D:\\workspace\\project", WorkspaceAccessMode.DEVELOP, true);
        var docs = new CodingWorkspaceRegistryPrompt.Entry(
                "workspace-docs", "D:\\workspace\\project\\docs", WorkspaceAccessMode.READ, false);
        var config = new CodingWorkspaceRegistryPrompt.Entry(
                "workspace-config", "D:\\workspace\\project\\config", WorkspaceAccessMode.DEVELOP, false);

        // Pass in non-sorted order to verify ordering
        String prompt = CodingWorkspaceRegistryPrompt.render(List.of(docs, config, main));

        assertThat(prompt)
                .contains(
                        "Use rootPath and its descendants as host absolute paths for file tools.",
                        "Use the matching workspaceRef with a normalized relativeWorkdir for execution_run;",
                        "use \".\" as relativeWorkdir for that workspace root.",
                        "Paths not listed here are not available unless workspace_attach succeeds.",
                        "<workspace_paths>",
                        "</workspace_paths>");

        // Verify current workspace is first and has current="true"
        int mainIndex = prompt.indexOf("workspaceRef=\"workspace-main\"");
        int configIndex = prompt.indexOf("workspaceRef=\"workspace-config\"");
        int docsIndex = prompt.indexOf("workspaceRef=\"workspace-docs\"");

        assertThat(mainIndex).isGreaterThan(0);
        assertThat(configIndex).isGreaterThan(mainIndex);
        assertThat(docsIndex).isGreaterThan(configIndex);

        assertThat(prompt)
                .contains(
                        "<workspace workspaceRef=\"workspace-main\" rootPath=\"D:\\workspace\\project\" mode=\"DEVELOP\" current=\"true\" />")
                .contains(
                        "<workspace workspaceRef=\"workspace-config\" rootPath=\"D:\\workspace\\project\\config\" mode=\"DEVELOP\" />")
                .contains(
                        "<workspace workspaceRef=\"workspace-docs\" rootPath=\"D:\\workspace\\project\\docs\" mode=\"READ\" />")
                .doesNotContain(
                        "safeDisplayName",
                        "source",
                        "status",
                        "ACTIVE",
                        "APPROVED_ATTACH",
                        "locationRef",
                        "physicalFingerprint");
    }

    @Test
    void escapesXmlSpecialCharactersInPaths() {
        var entry = new CodingWorkspaceRegistryPrompt.Entry(
                "ws-special", "/home/user/path with & < > \" ' chars", WorkspaceAccessMode.READ, false);

        String prompt = CodingWorkspaceRegistryPrompt.render(List.of(entry));

        assertThat(prompt)
                .contains("rootPath=\"/home/user/path with &amp; &lt; &gt; &quot; &apos; chars\"")
                .doesNotContain("< >");
    }

    @Test
    void rendersByteForByteIdenticalOutputDeterministically() {
        var main = new CodingWorkspaceRegistryPrompt.Entry("ws-1", "/path/1", WorkspaceAccessMode.DEVELOP, true);
        var second = new CodingWorkspaceRegistryPrompt.Entry("ws-2", "/path/2", WorkspaceAccessMode.READ, false);

        String first = CodingWorkspaceRegistryPrompt.render(List.of(second, main));
        String again = CodingWorkspaceRegistryPrompt.render(List.of(main, second));

        assertThat(first).isEqualTo(again);
        assertThat(first.getBytes(StandardCharsets.UTF_8)).isEqualTo(again.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void validatesEntryInvariants() {
        assertThatThrownBy(() -> new CodingWorkspaceRegistryPrompt.Entry(null, "/path", WorkspaceAccessMode.READ, true))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CodingWorkspaceRegistryPrompt.Entry("ws", null, WorkspaceAccessMode.READ, true))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CodingWorkspaceRegistryPrompt.Entry("ws", "/path", null, true))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CodingWorkspaceRegistryPrompt.Entry("  ", "/path", WorkspaceAccessMode.READ, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CodingWorkspaceRegistryPrompt.Entry("ws", "  ", WorkspaceAccessMode.READ, true))
                .isInstanceOf(IllegalArgumentException.class);
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

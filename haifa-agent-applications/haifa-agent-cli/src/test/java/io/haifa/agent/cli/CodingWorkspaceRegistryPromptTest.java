package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.project.hostworkspace.registry.HostWorkspaceRegistrySource;
import io.haifa.agent.project.hostworkspace.registry.HostWorkspaceRegistryStatus;
import io.haifa.agent.project.hostworkspace.registry.HostWorkspaceRegistryView;
import io.haifa.agent.project.hostworkspace.scope.HostDirectoryPermission;
import java.util.List;
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
                .doesNotContain("C:\\", "/home/", "realPath", "locationRef");
    }
}

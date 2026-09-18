package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalWorkspaceIdentityTest {
    @TempDir
    Path root;

    @Test
    void sameCanonicalWorkspaceHasStableVersionedIdentity() {
        LocalWorkspaceIdentity first = LocalWorkspaceIdentity.resolve(root);
        LocalWorkspaceIdentity second = LocalWorkspaceIdentity.resolve(root.resolve("."));

        assertThat(second.projectId()).isEqualTo(first.projectId());
        assertThat(second.workspaceId()).isEqualTo(first.workspaceId());
        assertThat(second.configurationId()).isEqualTo(first.configurationId());
        assertThat(first.projectId().value()).startsWith("local-project-v1-");
        assertThat(first.toString()).doesNotContain(root.toAbsolutePath().toString());
    }

    @Test
    void differentWorkspaceCannotShareProjectOrWorkspaceIdentity() throws Exception {
        Path other = Files.createDirectory(root.resolve("other"));

        LocalWorkspaceIdentity first = LocalWorkspaceIdentity.resolve(root);
        LocalWorkspaceIdentity second = LocalWorkspaceIdentity.resolve(other);

        assertThat(second.projectId()).isNotEqualTo(first.projectId());
        assertThat(second.workspaceId()).isNotEqualTo(first.workspaceId());
        assertThat(second.configurationId()).isNotEqualTo(first.configurationId());
    }

    @Test
    void rejectsSymbolicLinkWorkspaceRoot() throws Exception {
        Path target = Files.createDirectory(root.resolve("real"));
        Path link = root.resolve("link");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException | SecurityException exception) {
            Assumptions.assumeTrue(false, "symbolic links are unavailable on this test host");
            return;
        }

        assertThatThrownBy(() -> LocalWorkspaceIdentity.resolve(link))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbolic link");
    }
}

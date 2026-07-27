package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
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
        assertThat(second.bindingId()).isEqualTo(first.bindingId());
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
        assertThat(second.locationRef()).isNotEqualTo(first.locationRef());
    }
}

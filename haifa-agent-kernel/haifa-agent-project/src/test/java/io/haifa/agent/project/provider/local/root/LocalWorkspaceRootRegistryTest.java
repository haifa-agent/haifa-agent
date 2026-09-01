package io.haifa.agent.project.provider.local.root;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.project.root.WorkspaceRootAlias;
import io.haifa.agent.project.root.WorkspaceRootErrorCode;
import io.haifa.agent.project.root.WorkspaceRootException;
import io.haifa.agent.project.root.WorkspaceRootPermission;
import io.haifa.agent.project.root.WorkspaceRootStrategy;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalWorkspaceRootRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void singleMainCreation() {
        LocalWorkspaceRoot mainRoot = LocalWorkspaceRoot.main(tempDir, WorkspaceRootStrategy.GIT, false);
        LocalWorkspaceRootRegistry registry = LocalWorkspaceRootRegistry.singleMain(mainRoot);

        assertThat(registry.mainRoot()).isEqualTo(mainRoot);
        assertThat(registry.find(WorkspaceRootAlias.MAIN)).contains(mainRoot);
        assertThat(registry.allRoots()).containsExactly(mainRoot);
        assertThat(registry.contains(WorkspaceRootAlias.MAIN)).isTrue();
    }

    @Test
    void builderRejectsDuplicateAliases() {
        Path dir1 = tempDir.resolve("d1");
        Path dir2 = tempDir.resolve("d2");

        assertThatThrownBy(() -> LocalWorkspaceRootRegistry.builder()
                        .addRoot(LocalWorkspaceRoot.of(
                                WorkspaceRootAlias.MAIN,
                                dir1,
                                WorkspaceRootPermission.READ_WRITE,
                                WorkspaceRootStrategy.GIT,
                                false))
                        .addRoot(LocalWorkspaceRoot.of(
                                WorkspaceRootAlias.MAIN,
                                dir2,
                                WorkspaceRootPermission.READ_WRITE,
                                WorkspaceRootStrategy.GIT,
                                false))
                        .build())
                .isInstanceOf(WorkspaceRootException.class)
                .satisfies(e -> assertThat(((WorkspaceRootException) e).code())
                        .isEqualTo(WorkspaceRootErrorCode.DUPLICATE_ROOT_ALIAS));
    }

    @Test
    void permissionChecking() {
        Path mainPath = tempDir.resolve("main");
        Path docsPath = tempDir.resolve("docs");

        LocalWorkspaceRootRegistry registry = LocalWorkspaceRootRegistry.builder()
                .addRoot(LocalWorkspaceRoot.of(
                        WorkspaceRootAlias.MAIN,
                        mainPath,
                        WorkspaceRootPermission.READ_WRITE,
                        WorkspaceRootStrategy.GIT,
                        false))
                .addRoot(LocalWorkspaceRoot.of(
                        WorkspaceRootAlias.of("docs"),
                        docsPath,
                        WorkspaceRootPermission.READ_ONLY,
                        WorkspaceRootStrategy.PLAIN,
                        false))
                .build();

        // Main allows read and write
        registry.checkPermission(WorkspaceRootAlias.MAIN, WorkspaceRootPermission.READ_ONLY);
        registry.checkPermission(WorkspaceRootAlias.MAIN, WorkspaceRootPermission.READ_WRITE);

        // Docs allows read but denies write
        registry.checkPermission(WorkspaceRootAlias.of("docs"), WorkspaceRootPermission.READ_ONLY);
        assertThatThrownBy(() ->
                        registry.checkPermission(WorkspaceRootAlias.of("docs"), WorkspaceRootPermission.READ_WRITE))
                .isInstanceOf(WorkspaceRootException.class)
                .satisfies(e -> assertThat(((WorkspaceRootException) e).code())
                        .isEqualTo(WorkspaceRootErrorCode.ROOT_READ_ONLY));
    }
}

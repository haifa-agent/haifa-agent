package io.haifa.agent.project.hostworkspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.project.workspace.WorkspaceId;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class HostWorkspaceLocationStoreTest {
    private static final WorkspaceId WORKSPACE = new WorkspaceId("local-directory-ws-v1-unit");

    @TempDir
    Path directory;

    @Test
    void resolvesRegisteredDirectoryAndFingerprint() throws Exception {
        Path root = Files.createDirectory(directory.resolve("root"));
        var store = new HostWorkspaceLocationStore();
        store.register(WORKSPACE, root);

        assertThat(store.resolveVerified(WORKSPACE)).isEqualTo(root.toRealPath(LinkOption.NOFOLLOW_LINKS));
        assertThat(HostWorkspaceLocationStore.physicalFingerprintFor(root))
                .isEqualTo(
                        HostWorkspaceLocationStore.physicalFingerprintFor(root.toRealPath(LinkOption.NOFOLLOW_LINKS)));
    }

    @Test
    void rejectsPhysicalReplacementAtTheSameRegisteredPath() throws Exception {
        Path root = Files.createDirectory(directory.resolve("root"));
        var store = new HostWorkspaceLocationStore();
        store.register(WORKSPACE, root);
        String originalFingerprint = HostWorkspaceLocationStore.physicalFingerprintFor(root);

        deleteRecursively(root);
        Files.createDirectory(root);

        assertThat(HostWorkspaceLocationStore.physicalFingerprintFor(root)).isNotEqualTo(originalFingerprint);
        assertThatThrownBy(() -> store.resolveVerified(WORKSPACE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identity changed");
    }

    @Test
    void rejectsUnregisteredWorkspaceWithoutLeakingAPath() {
        var store = new HostWorkspaceLocationStore();
        assertThatThrownBy(() -> store.resolveVerified(new WorkspaceId("missing")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not registered");
    }

    @Test
    void rejectsSymbolicLinkRootOnRegistration() throws Exception {
        Path target = Files.createDirectory(directory.resolve("symlink-target"));
        Path link = directory.resolve("symlink-root");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException | SecurityException exception) {
            Assumptions.assumeTrue(false, "symbolic links are unavailable on this test host");
            return;
        }

        assertThatThrownBy(() -> new HostWorkspaceLocationStore().register(WORKSPACE, link))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("symbolic link or reparse point");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void rejectsWindowsJunctionRootOnRegistration() throws Exception {
        Path target = Files.createDirectory(directory.resolve("junction-target"));
        Path junction = directory.resolve("junction-root");
        Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", junction.toString(), target.toString())
                .redirectErrorStream(true)
                .start();
        Assumptions.assumeTrue(process.waitFor() == 0, "junction creation is unavailable on this host");

        assertThatThrownBy(() -> new HostWorkspaceLocationStore().register(WORKSPACE, junction))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("symbolic link or reparse point");
    }

    private static void deleteRecursively(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}

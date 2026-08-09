package io.haifa.agent.common.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecureFilePermissionsTest {
    @TempDir
    Path directory;

    @Test
    void freezesOneSupportedPermissionStrategyForItsDirectory() throws Exception {
        Path root = Files.createDirectory(directory.resolve("root"));
        Path file = Files.writeString(root.resolve("state.db"), "state");

        var strategy = SecureFilePermissions.strategyForDirectory(root);
        strategy.secureDirectory(root);
        strategy.secureFile(file);

        assertThatThrownBy(() -> strategy.secureFile(Files.writeString(directory.resolve("outside"), "outside")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("outside");
    }

    @Test
    void failsClosedWhenTheFrozenDirectoryIdentityChanges() throws Exception {
        Path root = Files.createDirectory(directory.resolve("root"));
        BasicFileAttributes originalAttributes = attributes(root);
        var originalFileStore = Files.getFileStore(root);
        var strategy = SecureFilePermissions.strategyForDirectory(root);
        strategy.secureDirectory(root);

        Files.move(root, directory.resolve("original"));
        Files.createDirectory(root);
        BasicFileAttributes replacementAttributes = attributes(root);
        if (sameIdentity(originalAttributes, replacementAttributes)) {
            BasicFileAttributeView view = Files.getFileAttributeView(root, BasicFileAttributeView.class);
            if (view != null) {
                view.setTimes(
                        null,
                        null,
                        FileTime.fromMillis(originalAttributes.creationTime().toMillis() + 2_000));
                replacementAttributes = attributes(root);
            }
        }
        Assumptions.assumeFalse(
                sameIdentity(originalAttributes, replacementAttributes),
                "filesystem cannot expose a distinct replacement directory identity");

        if (System.getProperty("os.name", "").startsWith("Mac")) {
            assertThat(Files.getFileStore(root)).isEqualTo(originalFileStore);
        }

        assertThatThrownBy(strategy::validateRoot)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("identity changed");

        Path replacementFile = Files.writeString(root.resolve("replacement.db"), "replacement");
        assertThatThrownBy(() -> strategy.secureExistingFiles(List.of(replacementFile)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("identity changed");
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean sameIdentity(BasicFileAttributes first, BasicFileAttributes second) {
        return Objects.equals(first.fileKey(), second.fileKey())
                && first.creationTime().equals(second.creationTime());
    }

    @Test
    void rejectsSymbolicLinkFilesWithoutFollowingThem() throws Exception {
        Path root = Files.createDirectory(directory.resolve("root"));
        Path target = Files.writeString(root.resolve("target"), "target");
        Path link = root.resolve("link");
        try {
            Files.createSymbolicLink(link, target.getFileName());
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.abort("symbolic links are unavailable on this host");
        }

        var strategy = SecureFilePermissions.strategyForDirectory(root);
        assertThatThrownBy(() -> strategy.secureFile(link))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("invalid type");
        assertThatThrownBy(() -> strategy.secureExistingFiles(List.of(link)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("invalid type");
    }

    @Test
    void repairsPermissionsChangedAfterTheStrategyWasDetected() throws Exception {
        Path root = Files.createDirectory(directory.resolve("root"));
        Path file = Files.writeString(root.resolve("state.db"), "state");
        Path sidecar = Files.writeString(root.resolve("state.db-wal"), "wal");
        var strategy = SecureFilePermissions.strategyForDirectory(root);
        strategy.secureExistingFiles(List.of(file, sidecar));

        if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(file, EnumSet.allOf(PosixFilePermission.class));
            Files.setPosixFilePermissions(sidecar, EnumSet.allOf(PosixFilePermission.class));
            strategy.secureExistingFiles(List.of(file, sidecar, root.resolve("missing-journal")));
            assertThat(Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS))
                    .isEqualTo(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            assertThat(Files.getPosixFilePermissions(sidecar, LinkOption.NOFOLLOW_LINKS))
                    .isEqualTo(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            return;
        }

        AclFileAttributeView acl =
                Files.getFileAttributeView(file, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        List<java.nio.file.attribute.AclEntry> expected = acl.getAcl();
        acl.setAcl(List.of());
        strategy.secureExistingFiles(List.of(file, sidecar, root.resolve("missing-journal")));
        assertThat(acl.getAcl()).isEqualTo(expected);
    }
}

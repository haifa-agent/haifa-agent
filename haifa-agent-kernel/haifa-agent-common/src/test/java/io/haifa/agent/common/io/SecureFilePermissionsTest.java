package io.haifa.agent.common.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
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
        var strategy = SecureFilePermissions.strategyForDirectory(root);
        strategy.secureDirectory(root);

        Files.move(root, directory.resolve("original"));
        Files.createDirectory(root);

        assertThatThrownBy(strategy::validateRoot)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("identity changed");
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
    }

    @Test
    void repairsPermissionsChangedAfterTheStrategyWasDetected() throws Exception {
        Path root = Files.createDirectory(directory.resolve("root"));
        Path file = Files.writeString(root.resolve("state.db"), "state");
        var strategy = SecureFilePermissions.strategyForDirectory(root);
        strategy.secureFile(file);

        if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(file, EnumSet.allOf(PosixFilePermission.class));
            strategy.secureFile(file);
            assertThat(Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS))
                    .isEqualTo(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            return;
        }

        AclFileAttributeView acl =
                Files.getFileAttributeView(file, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        List<java.nio.file.attribute.AclEntry> expected = acl.getAcl();
        acl.setAcl(List.of());
        strategy.secureFile(file);
        assertThat(acl.getAcl()).isEqualTo(expected);
    }
}

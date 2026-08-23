package io.haifa.agent.auth.localmodel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Fail-closed current-user filesystem permission policy. */
final class LocalModelAuthFilePermissions {
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------");

    void secure(Path path, boolean directory) throws IOException {
        rejectSymbolicLink(path);
        var posix = Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView.class);
        if (posix != null) {
            Set<PosixFilePermission> expected = directory ? DIRECTORY_PERMISSIONS : FILE_PERMISSIONS;
            Files.setPosixFilePermissions(path, expected);
            if (!Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(expected)) {
                throw new IllegalStateException("Local model auth POSIX permissions are not private");
            }
            return;
        }
        AclFileAttributeView acl =
                Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) {
            throw new IllegalStateException("Local model auth filesystem has no supported permission model");
        }
        UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
        requireCurrentUser(path, owner);
        AclEntry.Builder entry = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        acl.setAcl(List.of(entry.build()));
        List<AclEntry> actual = acl.getAcl();
        if (actual.isEmpty()
                || actual.stream()
                        .anyMatch(value ->
                                value.type() != AclEntryType.ALLOW || !samePrincipal(value.principal(), owner))) {
            throw new IllegalStateException("Local model auth Windows ACL is not current-user only");
        }
    }

    void rejectSymbolicLink(Path path) {
        if (Files.isSymbolicLink(path)) {
            throw new IllegalStateException("Local model auth path must not be a symbolic link");
        }
    }

    private static void requireCurrentUser(Path path, UserPrincipal owner) throws IOException {
        String userName = System.getProperty("user.name");
        if (userName == null || userName.isBlank()) throw new IllegalStateException("current user is unavailable");
        UserPrincipal current =
                path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(userName);
        if (!samePrincipal(owner, current)) {
            throw new IllegalStateException("Local model auth path is not owned by the current user");
        }
    }

    private static boolean samePrincipal(UserPrincipal first, UserPrincipal second) {
        if (first.equals(second)) return true;
        String firstName = first.getName().replace('/', '\\').toLowerCase(Locale.ROOT);
        String secondName = second.getName().replace('/', '\\').toLowerCase(Locale.ROOT);
        return firstName.equals(secondName)
                || firstName.endsWith("\\" + secondName)
                || secondName.endsWith("\\" + firstName);
    }
}

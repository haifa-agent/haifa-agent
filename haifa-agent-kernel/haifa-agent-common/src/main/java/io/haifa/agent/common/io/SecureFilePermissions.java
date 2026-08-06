package io.haifa.agent.common.io;

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
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Applies the storage-file permission baseline without following symbolic links.
 *
 * <p>POSIX stores use 0700 directories and 0600 files. ACL stores grant only the
 * current process user full access; an application that needs service accounts must add them
 * through a higher-level, explicit policy rather than weakening this baseline.
 */
public final class SecureFilePermissions {
    private static final Set<PosixFilePermission> POSIX_DIRECTORY =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> POSIX_FILE =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private SecureFilePermissions() {}

    public static void secureDirectory(Path directory) throws IOException {
        apply(directory, true);
    }

    public static void secureFile(Path file) throws IOException {
        apply(file, false);
    }

    private static void apply(Path path, boolean directory) throws IOException {
        Path target = path.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(target)
                || !Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                || directory != Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("secure storage target has an invalid type");
        }
        if (Files.getFileStore(target).supportsFileAttributeView("posix")) {
            Set<PosixFilePermission> expected = directory ? POSIX_DIRECTORY : POSIX_FILE;
            Files.setPosixFilePermissions(target, expected);
            if (!Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS)
                    .equals(expected)) {
                throw new IOException("secure POSIX permissions could not be verified");
            }
            return;
        }
        AclFileAttributeView acl =
                Files.getFileAttributeView(target, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw new IOException("secure file permissions are unsupported");
        UserPrincipal current = target.getFileSystem()
                .getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        var builder = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(current)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) {
            builder.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        }
        AclEntry expected = builder.build();
        List<AclEntry> expectedAcl = List.of(expected);
        if (acl.getAcl().equals(expectedAcl)) {
            return;
        }
        acl.setAcl(expectedAcl);
        if (!acl.getAcl().equals(expectedAcl)) {
            throw new IOException("secure ACL could not be verified");
        }
    }
}

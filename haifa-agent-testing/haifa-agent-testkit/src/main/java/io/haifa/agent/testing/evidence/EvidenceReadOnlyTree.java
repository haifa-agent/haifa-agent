package io.haifa.agent.testing.evidence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Applies and verifies the immutable evidence baseline on POSIX and Windows filesystems. */
public final class EvidenceReadOnlyTree {
    private static final Set<PosixFilePermission> POSIX_DIRECTORY =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE);
    private static final Set<AclEntryPermission> WINDOWS_READ_ONLY = EnumSet.of(
            AclEntryPermission.READ_DATA,
            AclEntryPermission.READ_NAMED_ATTRS,
            AclEntryPermission.READ_ATTRIBUTES,
            AclEntryPermission.READ_ACL,
            AclEntryPermission.SYNCHRONIZE,
            AclEntryPermission.EXECUTE);

    private EvidenceReadOnlyTree() {}

    public static void apply(Path root) throws IOException {
        Path target = root.toAbsolutePath().normalize().toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
            throw new IOException("evidence root must be a real directory");
        }
        List<Path> paths;
        try (var stream = Files.walk(target)) {
            paths = stream.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            if (Files.isSymbolicLink(path)) {
                EvidenceSymlinkTarget.requireInternal(target, path);
                continue;
            }
            if (isReadOnly(path)) {
                continue;
            }
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                applyPosix(path);
            } else {
                applyWindows(path);
            }
        }
    }

    public static boolean isReadOnly(Path path) throws IOException {
        Path target = path.toAbsolutePath().normalize();
        if (Files.getFileStore(target).supportsFileAttributeView("posix")) {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS);
            return permissions.stream()
                    .noneMatch(permission -> permission == PosixFilePermission.OWNER_WRITE
                            || permission == PosixFilePermission.GROUP_WRITE
                            || permission == PosixFilePermission.OTHERS_WRITE);
        }
        AclFileAttributeView acl =
                Files.getFileAttributeView(target, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null || acl.getAcl().size() != 1) return false;
        AclEntry entry = acl.getAcl().getFirst();
        boolean aclReadOnly =
                entry.type() == AclEntryType.ALLOW && entry.permissions().equals(WINDOWS_READ_ONLY);
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            DosFileAttributeView dos =
                    Files.getFileAttributeView(target, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            return aclReadOnly && dos != null && dos.readAttributes().isReadOnly();
        }
        return aclReadOnly;
    }

    private static void applyPosix(Path path) throws IOException {
        Set<PosixFilePermission> permissions;
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            permissions = POSIX_DIRECTORY;
        } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            boolean executable = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
                    .contains(PosixFilePermission.OWNER_EXECUTE);
            permissions = executable
                    ? Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE)
                    : Set.of(PosixFilePermission.OWNER_READ);
        } else {
            throw new IOException("evidence tree contains an unsupported entry");
        }
        Files.setPosixFilePermissions(path, permissions);
        if (!Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(permissions)) {
            throw new IOException("read-only POSIX evidence permissions could not be verified");
        }
    }

    private static void applyWindows(Path path) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("evidence tree contains an unsupported entry");
        }
        AclFileAttributeView acl =
                Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw new IOException("read-only evidence ACL is unsupported");
        UserPrincipal current = path.getFileSystem()
                .getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        AclEntry expected = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(current)
                .setPermissions(WINDOWS_READ_ONLY)
                .build();
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            DosFileAttributeView dos =
                    Files.getFileAttributeView(path, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (dos == null) throw new IOException("read-only DOS attributes are unsupported");
            dos.setReadOnly(true);
        }
        acl.setAcl(List.of(expected));
        if (!isReadOnly(path)) {
            throw new IOException("read-only Windows evidence ACL could not be verified");
        }
    }
}

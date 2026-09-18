package io.haifa.agent.common.io;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
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
        strategyForDirectory(directory).secureDirectory(directory);
    }

    public static void secureFile(Path file) throws IOException {
        Path target = file.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) throw new IOException("secure storage file must have a parent directory");
        strategyForDirectory(parent).secureFile(target);
    }

    /**
     * Detects and freezes the permission view for one trusted storage directory.
     *
     * <p>The returned strategy still validates the no-follow target type and the directory identity
     * on every call. It caches only FileStore capabilities and the current ACL principal, never a
     * conclusion that a file remains safe.
     */
    public static PermissionStrategy strategyForDirectory(Path directory) throws IOException {
        ValidatedTarget validatedRoot = normalizeAndRequireType(directory, true);
        Path root = validatedRoot.path();
        StorageIdentity rootIdentity = storageIdentity(validatedRoot);
        FileStore store = Files.getFileStore(root);
        if (store.supportsFileAttributeView("posix")) {
            return new DefaultPermissionStrategy(root, rootIdentity, macOsFileStore(store), true, null);
        }
        AclFileAttributeView acl =
                Files.getFileAttributeView(root, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw new IOException("secure file permissions are unsupported");
        UserPrincipal current = root.getFileSystem()
                .getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        return new DefaultPermissionStrategy(root, rootIdentity, macOsFileStore(store), false, current);
    }

    public interface PermissionStrategy {
        void validateRoot() throws IOException;

        void secureDirectory(Path directory) throws IOException;

        void secureFile(Path file) throws IOException;

        /**
         * Secures every currently existing file in one bounded operation. The built-in strategy
         * validates the frozen directory identity once for the batch; the compatibility default
         * retains per-file validation. Symbolic links, directories, and files outside the frozen
         * root still fail closed.
         */
        default void secureExistingFiles(List<Path> files) throws IOException {
            Objects.requireNonNull(files, "files must not be null");
            boolean existingFile = false;
            for (Path file : files) {
                Path target = Objects.requireNonNull(file, "file must not be null")
                        .toAbsolutePath()
                        .normalize();
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    existingFile = true;
                    secureFile(target);
                }
            }
            if (!existingFile) validateRoot();
        }
    }

    private record DefaultPermissionStrategy(
            Path root, StorageIdentity rootIdentity, FileStore macOsFileStore, boolean posix, UserPrincipal current)
            implements PermissionStrategy {
        private DefaultPermissionStrategy {
            root = root.toAbsolutePath().normalize();
        }

        @Override
        public void secureDirectory(Path directory) throws IOException {
            Path target = normalizeAndRequireType(directory, true).path();
            if (!target.equals(root)) throw new IOException("secure directory is outside the frozen permission root");
            requireRootIdentity();
            apply(target, true);
        }

        @Override
        public void validateRoot() throws IOException {
            requireRootIdentity();
        }

        @Override
        public void secureFile(Path file) throws IOException {
            requireRootIdentity();
            secureFileUnderValidatedRoot(file);
        }

        @Override
        public void secureExistingFiles(List<Path> files) throws IOException {
            Objects.requireNonNull(files, "files must not be null");
            requireRootIdentity();
            for (Path file : files) {
                Path target = Objects.requireNonNull(file, "file must not be null")
                        .toAbsolutePath()
                        .normalize();
                if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) continue;
                try {
                    secureFileUnderValidatedRoot(target);
                } catch (NoSuchFileException ignored) {
                    // SQLite sidecars may disappear between discovery and permission repair.
                } catch (IOException exception) {
                    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) continue;
                    throw exception;
                }
            }
        }

        private void secureFileUnderValidatedRoot(Path file) throws IOException {
            Path target = normalizeAndRequireType(file, false).path();
            if (!root.equals(target.getParent())) {
                throw new IOException("secure file is outside the frozen permission root");
            }
            apply(target, false);
        }

        private void requireRootIdentity() throws IOException {
            ValidatedTarget currentRoot = normalizeAndRequireType(root, true);
            if (!rootIdentity.equals(storageIdentity(currentRoot))) {
                throw new IOException("secure storage directory identity changed");
            }
            if (macOsFileStore != null && !macOsFileStore.equals(Files.getFileStore(currentRoot.path()))) {
                throw new IOException("secure storage directory identity changed");
            }
        }

        private void apply(Path target, boolean directory) throws IOException {
            if (posix) {
                applyPosix(target, directory);
                return;
            }
            applyAcl(target, directory, current);
        }
    }

    private static ValidatedTarget normalizeAndRequireType(Path path, boolean directory) throws IOException {
        Path target = path.toAbsolutePath().normalize();
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException exception) {
            throw new IOException("secure storage target has an invalid type", exception);
        }
        if (attributes.isSymbolicLink() || directory != attributes.isDirectory()) {
            throw new IOException("secure storage target has an invalid type");
        }
        return new ValidatedTarget(target, attributes);
    }

    private static StorageIdentity storageIdentity(ValidatedTarget target) throws IOException {
        Object fileKey = target.attributes().fileKey();
        Path fallbackRealPath = fileKey == null ? target.path().toRealPath(LinkOption.NOFOLLOW_LINKS) : null;
        return new StorageIdentity(
                fallbackRealPath, fileKey, target.attributes().creationTime());
    }

    private static FileStore macOsFileStore(FileStore store) {
        return System.getProperty("os.name", "").startsWith("Mac") ? store : null;
    }

    private record ValidatedTarget(Path path, BasicFileAttributes attributes) {}

    private record StorageIdentity(
            Path fallbackRealPath, Object fileKey, java.nio.file.attribute.FileTime creationTime) {}

    private static void applyPosix(Path target, boolean directory) throws IOException {
        Set<PosixFilePermission> expected = directory ? POSIX_DIRECTORY : POSIX_FILE;
        Files.setPosixFilePermissions(target, expected);
        if (!Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS).equals(expected)) {
            throw new IOException("secure POSIX permissions could not be verified");
        }
    }

    private static void applyAcl(Path target, boolean directory, UserPrincipal current) throws IOException {
        AclFileAttributeView acl =
                Files.getFileAttributeView(target, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw new IOException("secure file permissions are unsupported");
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

package io.haifa.agent.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/** Plaintext personal-computer auth store with strict local filesystem containment. */
final class CodingAuthFileStore {
    static final int SCHEMA_VERSION = 1;
    static final int MAX_FILE_BYTES = 1024 * 1024;

    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------");

    private final Path file;
    private final Path directory;
    private final Path lockFile;
    private final ObjectMapper json;
    private final ReentrantLock processLock = new ReentrantLock();

    CodingAuthFileStore(Path file, ObjectMapper json) {
        Path configured = java.util.Objects.requireNonNull(file, "file must not be null")
                .toAbsolutePath()
                .normalize();
        if (configured.getParent() == null
                || !"auth.json".equals(configured.getFileName().toString())) {
            throw new IllegalArgumentException("Coding auth store must target an auth.json file");
        }
        this.file = configured;
        this.directory = configured.getParent();
        this.lockFile = directory.resolve("auth.json.lock");
        this.json = java.util.Objects.requireNonNull(json, "json must not be null");
    }

    static CodingAuthFileStore defaultStore(ObjectMapper json) {
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            throw new IllegalStateException("user.home is unavailable");
        }
        return new CodingAuthFileStore(Path.of(userHome, ".haifa-agent", "auth.json"), json);
    }

    Path file() {
        return file;
    }

    Optional<CodingAuthCredential> find(String reference) {
        CodingAuthCredential probe = CodingAuthCredential.apiKey(reference, "probe");
        return withFileLock(() -> Optional.ofNullable(readAll().get(probe.reference())));
    }

    List<CodingAuthCredential> list() {
        return withFileLock(() -> List.copyOf(readAll().values()));
    }

    void save(CodingAuthCredential credential) {
        java.util.Objects.requireNonNull(credential, "credential must not be null");
        withFileLock(() -> {
            Map<String, CodingAuthCredential> credentials = readAll();
            credentials.put(credential.reference(), credential);
            writeAll(credentials);
            return null;
        });
    }

    boolean delete(String reference) {
        CodingAuthCredential probe = CodingAuthCredential.apiKey(reference, "probe");
        return withFileLock(() -> {
            Map<String, CodingAuthCredential> credentials = readAll();
            if (credentials.remove(probe.reference()) == null) return false;
            if (credentials.isEmpty()) {
                Files.deleteIfExists(file);
            } else {
                writeAll(credentials);
            }
            return true;
        });
    }

    private <T> T withFileLock(IoSupplier<T> operation) {
        processLock.lock();
        try {
            prepareDirectory();
            rejectSymbolicLink(lockFile);
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock ignored = tryFileLock(channel)) {
                securePath(lockFile, false);
                return operation.get();
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Coding auth store operation failed", exception);
        } finally {
            processLock.unlock();
        }
    }

    private FileLock tryFileLock(FileChannel channel) throws IOException {
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IllegalStateException("Coding auth store is locked by another process");
            return lock;
        } catch (OverlappingFileLockException exception) {
            throw new IllegalStateException("Coding auth store is already locked in this process", exception);
        }
    }

    private void prepareDirectory() throws IOException {
        rejectSymbolicLink(directory);
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Coding auth path is not a directory");
            }
        } else {
            Files.createDirectories(directory);
        }
        securePath(directory, true);
    }

    private Map<String, CodingAuthCredential> readAll() throws IOException {
        rejectSymbolicLink(file);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return new LinkedHashMap<>();
        securePath(file, false);
        long size = Files.size(file);
        if (size < 1 || size > MAX_FILE_BYTES) throw new IllegalStateException("Coding auth file size is invalid");
        byte[] bytes = Files.readAllBytes(file);
        try {
            JsonNode root = json.readTree(bytes);
            if (!root.isObject() || root.path("version").asInt(-1) != SCHEMA_VERSION) {
                throw new IllegalStateException("Coding auth file schema is invalid");
            }
            JsonNode credentials = root.get("credentials");
            if (credentials == null || !credentials.isObject()) {
                throw new IllegalStateException("Coding auth credentials object is invalid");
            }
            Map<String, CodingAuthCredential> result = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = credentials.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                CodingAuthCredential value = parseCredential(field.getKey(), field.getValue());
                if (result.put(value.reference(), value) != null) {
                    throw new IllegalStateException("Coding auth file contains duplicate credentials");
                }
            }
            return result;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (RuntimeException | IOException exception) {
            throw new IllegalStateException("Coding auth file is corrupted", exception);
        }
    }

    private CodingAuthCredential parseCredential(String reference, JsonNode node) {
        if (!node.isObject()) throw new IllegalStateException("Coding auth credential entry is invalid");
        String kind = requiredText(node, "kind");
        if ("API_KEY".equals(kind)) {
            requireExactFields(node, Set.of("kind", "api_key"));
            return CodingAuthCredential.apiKey(reference, requiredText(node, "api_key"));
        }
        if ("OAUTH2".equals(kind)) {
            requireExactFields(
                    node,
                    Set.of(
                            "kind",
                            "access_token",
                            "refresh_token",
                            "expires_at_epoch_millis",
                            "account_id",
                            "client_registration_ref",
                            "issued_at_epoch_millis"));
            return CodingAuthCredential.oauth2(
                    reference,
                    requiredText(node, "access_token"),
                    requiredText(node, "refresh_token"),
                    requiredLong(node, "expires_at_epoch_millis"),
                    requiredText(node, "account_id"),
                    requiredText(node, "client_registration_ref"),
                    requiredLong(node, "issued_at_epoch_millis"));
        }
        throw new IllegalStateException("Coding auth credential kind is unsupported");
    }

    private void writeAll(Map<String, CodingAuthCredential> credentials) throws IOException {
        ObjectNode root = json.createObjectNode();
        root.put("version", SCHEMA_VERSION);
        ObjectNode values = root.putObject("credentials");
        credentials.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            CodingAuthCredential credential = entry.getValue();
            ObjectNode node = values.putObject(entry.getKey());
            node.put("kind", credential.kind().name());
            if (credential.kind() == CodingAuthCredential.Kind.API_KEY) {
                node.put("api_key", credential.apiKey());
            } else {
                node.put("access_token", credential.accessToken());
                node.put("refresh_token", credential.refreshToken());
                node.put("expires_at_epoch_millis", credential.expiresAtEpochMillis());
                node.put("account_id", credential.accountId());
                node.put("client_registration_ref", credential.clientRegistrationRef());
                node.put("issued_at_epoch_millis", credential.issuedAtEpochMillis());
            }
        });
        byte[] bytes = json.writeValueAsBytes(root);
        if (bytes.length > MAX_FILE_BYTES) throw new IllegalStateException("Coding auth file exceeds the size limit");

        Path temporary = Files.createTempFile(directory, ".auth-", ".tmp");
        boolean moved = false;
        try {
            securePath(temporary, false);
            try (FileChannel channel =
                    FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IllegalStateException(
                        "Coding auth filesystem does not support atomic replacement", exception);
            }
            moved = true;
            securePath(file, false);
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    private void securePath(Path path, boolean directoryPath) throws IOException {
        var posix = Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView.class);
        if (posix != null) {
            Set<PosixFilePermission> expected = directoryPath ? DIRECTORY_PERMISSIONS : FILE_PERMISSIONS;
            Files.setPosixFilePermissions(path, expected);
            if (!Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(expected)) {
                throw new IllegalStateException("Coding auth POSIX permissions are not private");
            }
            return;
        }
        AclFileAttributeView acl =
                Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw new IllegalStateException("Coding auth filesystem has no supported permission model");
        UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
        requireCurrentUser(owner);
        AclEntry.Builder entry = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directoryPath) entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        acl.setAcl(List.of(entry.build()));
        List<AclEntry> actual = acl.getAcl();
        if (actual.isEmpty()
                || actual.stream()
                        .anyMatch(value ->
                                value.type() != AclEntryType.ALLOW || !samePrincipal(value.principal(), owner))) {
            throw new IllegalStateException("Coding auth Windows ACL is not current-user only");
        }
    }

    private void requireCurrentUser(UserPrincipal owner) throws IOException {
        String userName = System.getProperty("user.name");
        if (userName == null || userName.isBlank()) throw new IllegalStateException("current user is unavailable");
        UserPrincipal current =
                file.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(userName);
        if (!samePrincipal(owner, current)) {
            throw new IllegalStateException("Coding auth path is not owned by the current user");
        }
    }

    private static boolean samePrincipal(UserPrincipal first, UserPrincipal second) {
        if (first.equals(second)) return true;
        String firstName = first.getName().replace('/', '\\').toLowerCase(java.util.Locale.ROOT);
        String secondName = second.getName().replace('/', '\\').toLowerCase(java.util.Locale.ROOT);
        return firstName.equals(secondName)
                || firstName.endsWith("\\" + secondName)
                || secondName.endsWith("\\" + firstName);
    }

    private static void rejectSymbolicLink(Path path) {
        if (Files.isSymbolicLink(path)) throw new IllegalStateException("Coding auth path must not be a symbolic link");
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalStateException("Coding auth credential field is invalid");
        }
        return value.textValue();
    }

    private static long requiredLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong() || value.longValue() < 1) {
            throw new IllegalStateException("Coding auth credential timestamp is invalid");
        }
        return value.longValue();
    }

    private static void requireExactFields(JsonNode node, Set<String> allowed) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        if (!allowed.containsAll(names) || !names.containsAll(allowed)) {
            throw new IllegalStateException("Coding auth credential schema contains unexpected fields");
        }
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }
}
